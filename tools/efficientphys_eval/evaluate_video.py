#!/usr/bin/env python
"""Evaluate EfficientPhys and a GREEN+FFT baseline on one local face video."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import cv2
import matplotlib.pyplot as plt
import numpy as np
import torch
from scipy import signal
from scipy.sparse import spdiags


@dataclass
class SignalResult:
    heart_rate_bpm: float
    sqi: float
    sqi_level: str
    spectral_peak_ratio: float
    window_hr_std_bpm: float
    sample_count: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--toolbox", required=True, type=Path)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--frame-depth", type=int, default=10)
    parser.add_argument("--window", type=int, default=180)
    parser.add_argument("--overlap", type=float, default=0.5)
    parser.add_argument(
        "--app-green-hr",
        type=float,
        default=None,
        help="Optional GREEN+FFT HR saved by the Android app for the same video.",
    )
    parser.add_argument(
        "--rotation",
        choices=("auto", "0", "90_cw", "90_ccw", "180"),
        default="auto",
        help="Rotate decoded frames before face detection; auto tests all orientations.",
    )
    return parser.parse_args()


def detect_largest_face(
    frame_bgr: np.ndarray, detector: cv2.CascadeClassifier
) -> tuple[int, int, int, int] | None:
    gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
    faces = detector.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5)
    if len(faces) == 0:
        return None
    return tuple(max(faces, key=lambda item: int(item[2]) * int(item[3])))


def rotate_frame(frame: np.ndarray, rotation: str) -> np.ndarray:
    if rotation == "0":
        return frame
    if rotation == "90_cw":
        return cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
    if rotation == "90_ccw":
        return cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
    if rotation == "180":
        return cv2.rotate(frame, cv2.ROTATE_180)
    raise ValueError(f"Unsupported rotation: {rotation}")


def choose_rotation(
    frames: list[np.ndarray], detector: cv2.CascadeClassifier, requested: str
) -> str:
    if requested != "auto":
        return requested

    sample_count = min(8, len(frames))
    sample_indices = np.linspace(0, min(len(frames) - 1, 120), sample_count).astype(int)
    scores: dict[str, tuple[float, float]] = {}
    for rotation in ("0", "90_cw", "90_ccw", "180"):
        valid = 0
        relative_areas: list[float] = []
        for index in sample_indices:
            oriented = rotate_frame(frames[int(index)], rotation)
            box = detect_largest_face(oriented, detector)
            if box is None:
                continue
            valid += 1
            relative_areas.append(
                float(box[2] * box[3]) / float(oriented.shape[0] * oriented.shape[1])
            )
        detection_ratio = valid / sample_count
        median_area = float(np.median(relative_areas)) if relative_areas else 0.0
        scores[rotation] = (detection_ratio, median_area)

    rotation = max(scores, key=scores.get)
    if scores[rotation][0] == 0:
        raise RuntimeError("No face detected in any tested video orientation")
    return rotation


def enlarge_square(
    box: tuple[int, int, int, int], width: int, height: int, coefficient: float = 1.5
) -> tuple[int, int, int, int]:
    x, y, w, h = box
    side = max(w, h) * coefficient
    cx = x + w / 2.0
    cy = y + h / 2.0
    left = max(0, int(round(cx - side / 2.0)))
    top = max(0, int(round(cy - side / 2.0)))
    right = min(width, int(round(cx + side / 2.0)))
    bottom = min(height, int(round(cy + side / 2.0)))
    return left, top, right - left, bottom - top


def read_video(
    video_path: Path, cascade_path: Path, requested_rotation: str
) -> tuple[np.ndarray, np.ndarray, float, float, str]:
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise RuntimeError(f"Cannot open video: {video_path}")
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    if not math.isfinite(fps) or fps <= 0:
        raise RuntimeError(f"Invalid video FPS: {fps}")

    detector = cv2.CascadeClassifier(str(cascade_path))
    if detector.empty():
        raise RuntimeError(f"Cannot load Haar cascade: {cascade_path}")

    raw_frames: list[np.ndarray] = []
    while True:
        ok, frame = capture.read()
        if not ok:
            break
        raw_frames.append(frame)
    capture.release()
    if len(raw_frames) < 181:
        raise RuntimeError(f"EfficientPhys needs at least 181 frames, got {len(raw_frames)}")

    rotation = choose_rotation(raw_frames, detector, requested_rotation)
    raw_frames = [rotate_frame(frame, rotation) for frame in raw_frames]

    first_box = None
    for frame in raw_frames[: min(30, len(raw_frames))]:
        first_box = detect_largest_face(frame, detector)
        if first_box is not None:
            break
    if first_box is None:
        raise RuntimeError("No face detected in the first 30 frames")

    height, width = raw_frames[0].shape[:2]
    crop_box = enlarge_square(first_box, width, height, 1.5)
    x, y, w, h = crop_box
    face_frames = np.empty((len(raw_frames), 72, 72, 3), dtype=np.float32)
    green_signal = np.empty(len(raw_frames), dtype=np.float64)
    frame_luma = np.empty(len(raw_frames), dtype=np.float64)

    valid_checks = 0
    checked = 0
    for index, frame in enumerate(raw_frames):
        if index % 30 == 0:
            checked += 1
            if detect_largest_face(frame, detector) is not None:
                valid_checks += 1
        crop = frame[y : y + h, x : x + w]
        resized = cv2.resize(crop, (72, 72), interpolation=cv2.INTER_AREA)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
        face_frames[index] = rgb
        green_signal[index] = regional_green_mean(rgb)
        frame_luma[index] = float(np.mean(cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)))

    face_valid_ratio = valid_checks / max(1, checked)
    return face_frames, green_signal, fps, face_valid_ratio, rotation


def regional_green_mean(rgb: np.ndarray) -> float:
    height, width = rgb.shape[:2]
    regions = (
        rgb[int(0.15 * height) : int(0.35 * height), int(0.30 * width) : int(0.70 * width), 1],
        rgb[int(0.45 * height) : int(0.75 * height), int(0.12 * width) : int(0.42 * width), 1],
        rgb[int(0.45 * height) : int(0.75 * height), int(0.58 * width) : int(0.88 * width), 1],
    )
    return float(np.mean([np.mean(region) for region in regions]))


def load_model(toolbox: Path, checkpoint: Path, frame_depth: int) -> torch.nn.Module:
    sys.path.insert(0, str(toolbox))
    from neural_methods.model.EfficientPhys import EfficientPhys

    raw_state = torch.load(str(checkpoint), map_location="cpu")
    if not isinstance(raw_state, dict):
        raise RuntimeError(f"Unsupported checkpoint type: {type(raw_state).__name__}")
    state = {}
    for key, value in raw_state.items():
        clean_key = key[7:] if key.startswith("module.") else key
        if clean_key in state:
            raise RuntimeError(f"Duplicate checkpoint key after prefix cleanup: {clean_key}")
        state[clean_key] = value

    model = EfficientPhys(frame_depth=frame_depth, img_size=72)
    incompatible = model.load_state_dict(state, strict=True)
    if incompatible.missing_keys or incompatible.unexpected_keys:
        raise RuntimeError(
            f"Checkpoint mismatch: missing={incompatible.missing_keys}, "
            f"unexpected={incompatible.unexpected_keys}"
        )
    model.eval()
    return model


def window_starts(length: int, window: int, overlap: float) -> list[int]:
    if length < window:
        return []
    step = max(1, int(round(window * (1.0 - overlap))))
    last = length - window
    starts = list(range(0, last + 1, step))
    if not starts or starts[-1] != last:
        starts.append(last)
    return starts


def infer_efficientphys(
    model: torch.nn.Module,
    face_frames: np.ndarray,
    fps: float,
    window: int,
    overlap: float,
) -> tuple[np.ndarray, list[float], list[float]]:
    standard_deviation = float(np.std(face_frames))
    if standard_deviation < 1e-6:
        raise RuntimeError("Video standard deviation is too small")
    standardized = (face_frames - float(np.mean(face_frames))) / standard_deviation
    nchw = np.ascontiguousarray(
        np.transpose(standardized, (0, 3, 1, 2)), dtype=np.float32
    )

    starts = window_starts(len(nchw), window, overlap)
    if not starts:
        raise RuntimeError("No valid EfficientPhys windows")
    accumulated = np.zeros(len(nchw), dtype=np.float64)
    weights = np.zeros(len(nchw), dtype=np.float64)
    inference_times: list[float] = []
    per_window_hr: list[float] = []
    blend = np.hanning(window)
    blend = np.maximum(blend, 0.05)

    with torch.no_grad():
        for start in starts:
            chunk = nchw[start : start + window]
            model_input = np.concatenate((chunk, chunk[-1:]), axis=0)
            tensor = torch.from_numpy(model_input)
            started = time.perf_counter()
            output = model(tensor).detach().cpu().numpy().reshape(-1)
            inference_times.append((time.perf_counter() - started) * 1000.0)
            if output.shape != (window,):
                raise RuntimeError(f"Unexpected model output shape: {output.shape}")
            if not np.isfinite(output).all():
                raise RuntimeError("EfficientPhys output contains NaN or Inf")
            accumulated[start : start + window] += output * blend
            weights[start : start + window] += blend
            per_window_hr.append(estimate_fft_hr(recover_bvp(output), fps)[0])

    valid = weights > 0
    waveform = np.zeros(len(nchw), dtype=np.float64)
    waveform[valid] = accumulated[valid] / weights[valid]
    first = int(np.argmax(valid))
    last = len(valid) - int(np.argmax(valid[::-1]))
    recovered = recover_bvp(waveform[first:last])
    recovered /= max(float(np.std(recovered)), 1e-8)
    return recovered, inference_times, per_window_hr


def smoothness_detrend(values: np.ndarray, lambda_value: float = 100.0) -> np.ndarray:
    values = np.asarray(values, dtype=np.float64)
    length = len(values)
    identity = np.identity(length)
    diagonals = np.array(
        [np.ones(length), -2.0 * np.ones(length), np.ones(length)]
    )
    difference = spdiags(diagonals, np.array([0, 1, 2]), length - 2, length).toarray()
    trend = np.linalg.solve(
        identity + (lambda_value**2) * difference.T.dot(difference), values
    )
    return values - trend


def recover_bvp(diff_normalized_prediction: np.ndarray) -> np.ndarray:
    return smoothness_detrend(np.cumsum(diff_normalized_prediction), 100.0)


def bandpass(values: np.ndarray, fps: float) -> np.ndarray:
    values = np.asarray(values, dtype=np.float64)
    nyquist = fps / 2.0
    high = min(2.5, nyquist * 0.95)
    sos = signal.butter(
        1, [0.75 / nyquist, high / nyquist], btype="bandpass", output="sos"
    )
    return signal.sosfiltfilt(sos, values)


def estimate_fft_hr(values: np.ndarray, fps: float) -> tuple[float, float]:
    filtered = bandpass(values, fps)
    nfft = 1 if len(filtered) == 0 else 2 ** (len(filtered) - 1).bit_length()
    frequencies, power = signal.periodogram(
        filtered, fs=fps, nfft=nfft, detrend=False, scaling="spectrum"
    )
    in_band = (frequencies >= 0.75) & (frequencies <= min(2.5, fps / 2.0))
    if not np.any(in_band):
        raise RuntimeError("No frequencies in HR band")
    band_frequencies = frequencies[in_band]
    band_power = power[in_band]
    peak_index = int(np.argmax(band_power))
    peak_ratio = float(band_power[peak_index] / max(np.sum(band_power), 1e-12))
    return float(band_frequencies[peak_index] * 60.0), peak_ratio


def estimate_green_like_current(
    green_signal: np.ndarray, duration_seconds: float
) -> tuple[np.ndarray, float]:
    indices = np.linspace(0, len(green_signal) - 1, 60).round().astype(int)
    sampled = green_signal[indices]
    standard_deviation = float(np.std(sampled))
    normalized = (sampled - float(np.mean(sampled))) / max(standard_deviation, 1e-6)
    sample_rate = len(normalized) / duration_seconds
    nyquist_max = sample_rate / 2.0 - 0.05
    upper = min(3.0, nyquist_max)
    best_hz = 1.2
    best_power = -math.inf
    for hz in np.linspace(0.75, upper, 181):
        n = np.arange(len(normalized))
        transform = np.sum(normalized * np.exp(-2j * np.pi * hz * n / sample_rate))
        power = float(np.abs(transform) ** 2)
        if power > best_power:
            best_power = power
            best_hz = float(hz)
    return normalized, float(np.clip(round(best_hz * 60.0), 45, 180))


def sqi_level(score: float) -> str:
    if score >= 80:
        return "excellent"
    if score >= 60:
        return "usable"
    if score >= 40:
        return "low"
    return "invalid"


def calculate_sqi(
    peak_ratio: float,
    per_window_hr: list[float],
    face_valid_ratio: float,
) -> tuple[float, float]:
    hr_std = float(np.std(per_window_hr)) if len(per_window_hr) > 1 else 0.0
    spectral_score = np.clip((peak_ratio - 0.15) / 0.45, 0.0, 1.0)
    consistency_score = np.clip(1.0 - hr_std / 15.0, 0.0, 1.0)
    score = 50.0 * spectral_score + 30.0 * consistency_score + 20.0 * face_valid_ratio
    return float(np.clip(score, 0.0, 100.0)), hr_std


def save_outputs(
    output_dir: Path,
    fps: float,
    efficient_waveform: np.ndarray,
    green_waveform: np.ndarray,
    efficient: SignalResult,
    green_hr: float,
    face_valid_ratio: float,
    inference_times: list[float],
    source: dict,
    app_green_hr: float | None,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    efficient_time = np.arange(len(efficient_waveform)) / fps
    green_time = np.arange(len(green_waveform)) / (len(green_waveform) / source["duration_seconds"])
    np.savetxt(
        output_dir / "efficientphys_bvp.csv",
        np.column_stack((efficient_time, efficient_waveform)),
        delimiter=",",
        header="time_seconds,normalized_bvp",
        comments="",
    )
    np.savetxt(
        output_dir / "green_baseline.csv",
        np.column_stack((green_time, green_waveform)),
        delimiter=",",
        header="time_seconds,normalized_green",
        comments="",
    )

    figure, axes = plt.subplots(2, 1, figsize=(11, 7), constrained_layout=True)
    axes[0].plot(efficient_time, efficient_waveform, color="#2457C5", linewidth=1.1)
    axes[0].set_title(
        f"EfficientPhys predicted BVP | HR={efficient.heart_rate_bpm:.1f} bpm "
        f"| SQI={efficient.sqi:.1f}"
    )
    axes[0].set_xlabel("Time (s)")
    axes[0].set_ylabel("Normalized amplitude")
    axes[0].grid(alpha=0.2)
    axes[1].plot(green_time, green_waveform, color="#0F9D74", linewidth=1.1)
    axes[1].set_title(f"GREEN+FFT 60-sample baseline | HR={green_hr:.1f} bpm")
    axes[1].set_xlabel("Time (s)")
    axes[1].set_ylabel("Normalized green")
    axes[1].grid(alpha=0.2)
    figure.savefig(output_dir / "bvp_green_comparison.png", dpi=180)
    plt.close(figure)

    report = {
        "source": source,
        "checkpoint": source["checkpoint"],
        "preprocessing": {
            "color_order": "RGB",
            "decoded_frame_rotation": source["rotation"],
            "face_crop": "first valid Haar face, square box enlarged by 1.5",
            "resize": [72, 72],
            "normalization": "global z-score over the complete video",
            "model_internal_operation": "temporal first difference",
            "prediction_recovery": "cumulative sum then smoothness-prior detrending (lambda=100)",
            "heart_rate_band_hz": [0.75, 2.5],
            "window_frames": 180,
            "window_overlap": 0.5,
        },
        "efficientphys": asdict(efficient),
        "green_fft_baseline": {
            "heart_rate_bpm": green_hr,
            "sample_count": int(len(green_waveform)),
            "description": "60 uniformly sampled frames using forehead/cheek green means",
        },
        "comparison": {
            "absolute_hr_difference_bpm": abs(efficient.heart_rate_bpm - green_hr),
            "app_green_fft_hr_bpm": app_green_hr,
            "efficientphys_vs_app_absolute_difference_bpm": (
                abs(efficient.heart_rate_bpm - app_green_hr)
                if app_green_hr is not None
                else None
            ),
            "offline_green_vs_app_absolute_difference_bpm": (
                abs(green_hr - app_green_hr) if app_green_hr is not None else None
            ),
            "has_contact_ground_truth": False,
        },
        "quality_inputs": {
            "face_valid_ratio": face_valid_ratio,
            "inference_time_ms_mean": float(np.mean(inference_times)),
            "inference_time_ms_p95": float(np.percentile(inference_times, 95)),
        },
        "limitations": [
            "SQI is an engineering quality score, not a calibrated probability.",
            "No synchronized contact PPG ground truth is available for this phone video.",
            "GREEN baseline uses box-derived forehead and cheek regions rather than MediaPipe landmarks.",
            "The Android app HR is a saved comparison value, not contact-sensor ground truth.",
        ],
    }
    (output_dir / "evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main() -> None:
    args = parse_args()
    cascade = args.toolbox / "dataset" / "haarcascade_frontalface_default.xml"
    frames, green_signal, fps, face_valid_ratio, rotation = read_video(
        args.video, cascade, args.rotation
    )
    model = load_model(args.toolbox, args.checkpoint, args.frame_depth)
    efficient_waveform, inference_times, per_window_hr = infer_efficientphys(
        model, frames, fps, args.window, args.overlap
    )
    efficient_hr, peak_ratio = estimate_fft_hr(efficient_waveform, fps)
    sqi, window_hr_std = calculate_sqi(peak_ratio, per_window_hr, face_valid_ratio)
    efficient = SignalResult(
        heart_rate_bpm=efficient_hr,
        sqi=sqi,
        sqi_level=sqi_level(sqi),
        spectral_peak_ratio=peak_ratio,
        window_hr_std_bpm=window_hr_std,
        sample_count=len(efficient_waveform),
    )
    duration = len(frames) / fps
    green_waveform, green_hr = estimate_green_like_current(green_signal, duration)
    source = {
        "video_name": args.video.name,
        "frame_count": int(len(frames)),
        "fps": fps,
        "duration_seconds": duration,
        "rotation": rotation,
        "checkpoint": str(args.checkpoint),
    }
    save_outputs(
        args.output_dir,
        fps,
        efficient_waveform,
        green_waveform,
        efficient,
        green_hr,
        face_valid_ratio,
        inference_times,
        source,
        args.app_green_hr,
    )
    print(json.dumps({
        "efficientphys_hr_bpm": efficient_hr,
        "efficientphys_sqi": sqi,
        "efficientphys_sqi_level": efficient.sqi_level,
        "green_fft_hr_bpm": green_hr,
        "absolute_difference_bpm": abs(efficient_hr - green_hr),
        "face_valid_ratio": face_valid_ratio,
        "inference_ms_mean": float(np.mean(inference_times)),
        "output_dir": str(args.output_dir),
    }, indent=2))


if __name__ == "__main__":
    main()
