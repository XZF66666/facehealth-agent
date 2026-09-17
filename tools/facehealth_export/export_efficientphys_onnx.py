#!/usr/bin/env python
"""Export the rPPG-Toolbox PURE EfficientPhys checkpoint to mobile ONNX."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import uuid
from pathlib import Path

import numpy as np
import onnx
import torch
import yaml
from onnxconverter_common import float16


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--toolbox", required=True, type=Path)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--frames", type=int, default=180)
    parser.add_argument("--height", type=int, default=72)
    parser.add_argument("--width", type=int, default=72)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--fp16-output", type=Path)
    return parser.parse_args()


class ReferenceMobileEfficientPhys(torch.nn.Module):
    """Reference path using the original rPPG-Toolbox forward method."""

    def __init__(self, model: torch.nn.Module):
        super().__init__()
        self.model = model

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        model_input = torch.cat((frames, frames[-1:, :, :, :]), dim=0)
        return self.model(model_input).squeeze(-1)


class MobileEfficientPhys(torch.nn.Module):
    """ONNX-compatible forward path, numerically checked against the source."""

    def __init__(self, model: torch.nn.Module, frame_depth: int):
        super().__init__()
        self.model = model
        self.frame_depth = frame_depth

    def temporal_shift(self, values: torch.Tensor) -> torch.Tensor:
        nt, channels, height, width = values.shape
        grouped = values.reshape(
            nt // self.frame_depth,
            self.frame_depth,
            channels,
            height,
            width,
        )
        fold = channels // 3
        left = torch.cat(
            (grouped[:, 1:, :fold], torch.zeros_like(grouped[:, :1, :fold])),
            dim=1,
        )
        right = torch.cat(
            (
                torch.zeros_like(grouped[:, :1, fold : 2 * fold]),
                grouped[:, :-1, fold : 2 * fold],
            ),
            dim=1,
        )
        unchanged = grouped[:, :, 2 * fold :]
        return torch.cat((left, right, unchanged), dim=2).reshape(
            nt, channels, height, width
        )

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        model_input = torch.cat((frames, frames[-1:, :, :, :]), dim=0)
        values = model_input[1:] - model_input[:-1]
        values = self.model.batch_norm(values)

        d1 = torch.tanh(self.model.motion_conv1(self.temporal_shift(values)))
        d2 = torch.tanh(self.model.motion_conv2(self.temporal_shift(d1)))
        g1 = torch.sigmoid(self.model.apperance_att_conv1(d2))
        g1 = self.model.attn_mask_1(g1)
        d4 = self.model.dropout_1(self.model.avg_pooling_1(d2 * g1))

        d5 = torch.tanh(self.model.motion_conv3(self.temporal_shift(d4)))
        d6 = torch.tanh(self.model.motion_conv4(self.temporal_shift(d5)))
        g2 = torch.sigmoid(self.model.apperance_att_conv2(d6))
        g2 = self.model.attn_mask_2(g2)
        d8 = self.model.dropout_3(self.model.avg_pooling_3(d6 * g2))

        flattened = d8.reshape(d8.shape[0], -1)
        dense = torch.tanh(self.model.final_dense_1(flattened))
        dense = self.model.dropout_4(dense)
        return self.model.final_dense_2(dense).squeeze(-1)


def checkpoint_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def load_config(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as stream:
        return yaml.safe_load(stream)


def config_value(config: dict, *paths: tuple[str, ...]):
    for path in paths:
        value = config
        try:
            for key in path:
                value = value[key]
            return value
        except (KeyError, TypeError):
            continue
    raise KeyError(f"Missing config value; tried {paths}")


def load_model(
    toolbox: Path,
    checkpoint: Path,
    frame_depth: int,
    height: int,
) -> tuple[ReferenceMobileEfficientPhys, MobileEfficientPhys]:
    sys.path.insert(0, str(toolbox))
    from neural_methods.model.EfficientPhys import EfficientPhys

    raw_state = torch.load(str(checkpoint), map_location="cpu", weights_only=True)
    if not isinstance(raw_state, dict):
        raise TypeError(f"Unsupported checkpoint type: {type(raw_state).__name__}")

    state = {}
    for key, value in raw_state.items():
        clean_key = key[7:] if key.startswith("module.") else key
        if clean_key in state:
            raise RuntimeError(f"Duplicate checkpoint key: {clean_key}")
        state[clean_key] = value

    model = EfficientPhys(frame_depth=frame_depth, img_size=height)
    incompatible = model.load_state_dict(state, strict=True)
    if incompatible.missing_keys or incompatible.unexpected_keys:
        raise RuntimeError(
            f"Checkpoint mismatch: missing={incompatible.missing_keys}, "
            f"unexpected={incompatible.unexpected_keys}"
        )
    model.eval()
    reference = ReferenceMobileEfficientPhys(model)
    exportable = MobileEfficientPhys(model, frame_depth)
    reference.eval()
    exportable.eval()
    return reference, exportable


def make_test_input(frames: int, height: int, width: int) -> np.ndarray:
    rng = np.random.default_rng(20260723)
    values = rng.standard_normal((frames, 3, height, width), dtype=np.float32)
    return np.ascontiguousarray(values, dtype=np.float32)


def run_ort(model_path: Path, values: np.ndarray) -> np.ndarray:
    runner = Path(__file__).with_name("ort_infer.py")
    token = uuid.uuid4().hex
    input_path = model_path.parent / f".ort_input_{token}.npy"
    output_path = model_path.parent / f".ort_output_{token}.npy"
    try:
        np.save(input_path, values)
        subprocess.run(
            [
                sys.executable,
                str(runner),
                "--model",
                str(model_path),
                "--input",
                str(input_path),
                "--output",
                str(output_path),
            ],
            check=True,
        )
        return np.load(output_path).astype(np.float32).reshape(-1)
    finally:
        input_path.unlink(missing_ok=True)
        output_path.unlink(missing_ok=True)


def compare(reference: np.ndarray, candidate: np.ndarray) -> dict:
    if reference.shape != candidate.shape:
        raise RuntimeError(
            f"Output shape mismatch: {reference.shape} != {candidate.shape}"
        )
    difference = np.abs(reference - candidate)
    correlation = float(np.corrcoef(reference, candidate)[0, 1])
    return {
        "shape": list(candidate.shape),
        "max_absolute_error": float(np.max(difference)),
        "mean_absolute_error": float(np.mean(difference)),
        "pearson": correlation,
        "nan_count": int(np.isnan(candidate).sum()),
        "inf_count": int(np.isinf(candidate).sum()),
    }


def verify_metrics(metrics: dict, max_error: float, min_pearson: float) -> None:
    if metrics["nan_count"] or metrics["inf_count"]:
        raise RuntimeError(f"Non-finite ONNX output: {metrics}")
    if metrics["max_absolute_error"] > max_error:
        raise RuntimeError(f"ONNX maximum error exceeds {max_error}: {metrics}")
    if metrics["pearson"] < min_pearson:
        raise RuntimeError(f"ONNX Pearson is below {min_pearson}: {metrics}")


def main() -> None:
    args = parse_args()
    if args.batch_size != 1:
        raise ValueError("Mobile EfficientPhys export currently supports batch-size=1")
    if args.frames % 10 != 0:
        raise ValueError("frames must be divisible by EfficientPhys frame depth 10")
    for path in (args.checkpoint, args.config):
        if not path.is_file():
            raise FileNotFoundError(path)

    config = load_config(args.config)
    print("[export] Config loaded", flush=True)
    frame_depth = int(
        config_value(config, ("MODEL", "EFFICIENTPHYS", "FRAME_DEPTH"))
    )
    configured_height = int(
        config_value(
            config,
            ("TEST", "DATA", "PREPROCESS", "RESIZE", "H"),
            ("TRAIN", "DATA", "PREPROCESS", "RESIZE", "H"),
        )
    )
    configured_width = int(
        config_value(
            config,
            ("TEST", "DATA", "PREPROCESS", "RESIZE", "W"),
            ("TRAIN", "DATA", "PREPROCESS", "RESIZE", "W"),
        )
    )
    if (args.height, args.width) != (configured_height, configured_width):
        raise ValueError(
            "Requested image size does not match config: "
            f"{args.height}x{args.width} != {configured_height}x{configured_width}"
        )
    if args.frames % frame_depth != 0:
        raise ValueError(f"frames must be divisible by frame depth {frame_depth}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    reference, wrapper = load_model(
        args.toolbox, args.checkpoint, frame_depth, args.height
    )
    print("[export] Checkpoint strictly loaded", flush=True)
    test_input = make_test_input(args.frames, args.height, args.width)
    tensor = torch.from_numpy(test_input)
    with torch.no_grad():
        pytorch_output = reference(tensor).cpu().numpy().astype(np.float32)
        exportable_output = wrapper(tensor).cpu().numpy().astype(np.float32)
    print("[export] Export wrapper matches source forward", flush=True)
    wrapper_metrics = compare(
        pytorch_output.reshape(-1), exportable_output.reshape(-1)
    )
    verify_metrics(wrapper_metrics, max_error=1e-6, min_pearson=0.999999)

    torch.onnx.export(
        wrapper,
        tensor,
        str(args.output),
        input_names=["input_frames"],
        output_names=["bvp_diff_normalized"],
        opset_version=args.opset,
        export_params=True,
        do_constant_folding=True,
        dynamo=False,
    )
    print("[export] FP32 ONNX written", flush=True)
    model = onnx.load(str(args.output))
    onnx.checker.check_model(model)
    onnx_output = run_ort(args.output, test_input)
    print("[export] FP32 ONNX Runtime inference completed", flush=True)
    fp32_metrics = compare(pytorch_output.reshape(-1), onnx_output)
    verify_metrics(fp32_metrics, max_error=1e-3, min_pearson=0.999)

    output_dir = args.output.parent
    np.save(output_dir / "efficientphys_test_input.npy", test_input)
    np.save(output_dir / "efficientphys_pytorch_output.npy", pytorch_output)
    np.save(output_dir / "efficientphys_onnx_output.npy", onnx_output)

    fp16_metrics = None
    if args.fp16_output is not None:
        args.fp16_output.parent.mkdir(parents=True, exist_ok=True)
        fp16_model = float16.convert_float_to_float16(
            model, keep_io_types=True, disable_shape_infer=False
        )
        onnx.save(fp16_model, str(args.fp16_output))
        print("[export] FP16 ONNX written", flush=True)
        onnx.checker.check_model(onnx.load(str(args.fp16_output)))
        fp16_output = run_ort(args.fp16_output, test_input)
        print("[export] FP16 ONNX Runtime inference completed", flush=True)
        fp16_metrics = compare(pytorch_output.reshape(-1), fp16_output)
        verify_metrics(fp16_metrics, max_error=2e-2, min_pearson=0.995)
        np.save(output_dir / "efficientphys_fp16_output.npy", fp16_output)

    metadata = {
        "model_name": "EfficientPhys",
        "model_version": "PURE-rPPG-Toolbox-FP16-v1",
        "checkpoint_name": args.checkpoint.name,
        "checkpoint_sha256": checkpoint_sha256(args.checkpoint),
        "training_dataset": "PURE",
        "input_name": "input_frames",
        "output_name": "bvp_diff_normalized",
        "input_shape": [args.frames, 3, args.height, args.width],
        "output_shape": [args.frames],
        "input_layout": "TCHW",
        "frame_count": args.frames,
        "frame_height": args.height,
        "frame_width": args.width,
        "color_order": "RGB",
        "fps": 30.0,
        "normalization": "global z-score over all RGB pixels in one video/window",
        "uses_frame_difference": True,
        "wrapper_appends_last_frame": True,
        "output_description": "diff_normalized_bvp_waveform",
        "postprocessing": (
            "cumulative sum, smoothness-prior detrend lambda=100, "
            "0.75-2.5 Hz band-pass"
        ),
        "onnx_opset": args.opset,
        "wrapper_verification": wrapper_metrics,
        "fp32_verification": fp32_metrics,
        "fp16_verification": fp16_metrics,
    }
    (output_dir / "efficientphys_metadata.json").write_text(
        json.dumps(metadata, indent=2), encoding="utf-8"
    )
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
