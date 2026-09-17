#!/usr/bin/env python
"""Verify an exported EfficientPhys model against saved reference tensors."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--max-error", type=float, default=2e-2)
    parser.add_argument("--min-pearson", type=float, default=0.995)
    args = parser.parse_args()

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    values = np.load(args.input).astype(np.float32)
    reference = np.load(args.reference).astype(np.float32).reshape(-1)
    if list(values.shape) != metadata["input_shape"]:
        raise ValueError(
            f"Input shape {list(values.shape)} != {metadata['input_shape']}"
        )

    onnx.checker.check_model(onnx.load(str(args.model)))
    session = ort.InferenceSession(
        str(args.model), providers=["CPUExecutionProvider"]
    )
    output = session.run(
        [metadata["output_name"]], {metadata["input_name"]: values}
    )[0].astype(np.float32).reshape(-1)
    if output.shape != reference.shape:
        raise RuntimeError(f"Shape mismatch: {output.shape} != {reference.shape}")

    difference = np.abs(output - reference)
    result = {
        "model": str(args.model),
        "shape": list(output.shape),
        "max_absolute_error": float(np.max(difference)),
        "mean_absolute_error": float(np.mean(difference)),
        "pearson": float(np.corrcoef(output, reference)[0, 1]),
        "nan_count": int(np.isnan(output).sum()),
        "inf_count": int(np.isinf(output).sum()),
    }
    print(json.dumps(result, indent=2))
    if result["nan_count"] or result["inf_count"]:
        raise RuntimeError("Output contains NaN or Inf")
    if result["max_absolute_error"] > args.max_error:
        raise RuntimeError("Maximum absolute error exceeds threshold")
    if result["pearson"] < args.min_pearson:
        raise RuntimeError("Pearson correlation is below threshold")


if __name__ == "__main__":
    main()
