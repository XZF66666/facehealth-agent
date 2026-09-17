#!/usr/bin/env python
"""Isolated ONNX Runtime process to avoid PyTorch/OpenMP runtime conflicts."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    values = np.load(args.input).astype(np.float32)
    session = ort.InferenceSession(
        str(args.model), providers=["CPUExecutionProvider"]
    )
    input_name = session.get_inputs()[0].name
    output = session.run(None, {input_name: values})[0]
    np.save(args.output, np.asarray(output, dtype=np.float32))


if __name__ == "__main__":
    main()
