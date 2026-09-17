#!/usr/bin/env python
"""Inspect EfficientPhys checkpoint keys without silently relaxing strict load."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    args = parser.parse_args()
    state = torch.load(str(args.checkpoint), map_location="cpu", weights_only=True)
    if not isinstance(state, dict):
        raise TypeError(type(state).__name__)
    summary = {
        "checkpoint": str(args.checkpoint),
        "key_count": len(state),
        "all_module_prefixed": all(key.startswith("module.") for key in state),
        "keys": {
            key: {"shape": list(value.shape), "dtype": str(value.dtype)}
            for key, value in state.items()
        },
    }
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
