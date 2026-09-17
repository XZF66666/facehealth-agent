# FaceHealth EfficientPhys Export

The mobile wrapper accepts one standardized RGB tensor with shape
`[180, 3, 72, 72]`. It duplicates the final frame internally before invoking
the unchanged rPPG-Toolbox model. The output is 180 DiffNormalized BVP samples.

```powershell
python tools\facehealth_export\export_efficientphys_onnx.py `
  --toolbox <path-to-rPPG-Toolbox> `
  --checkpoint <path-to-EfficientPhys-checkpoint> `
  --config <path-to-EfficientPhys-config> `
  --output tools\facehealth_export\output\efficientphys_pure.onnx `
  --fp16-output tools\facehealth_export\output\efficientphys_pure_fp16.onnx `
  --batch-size 1 --frames 180 --height 72 --width 72 --opset 17
```

The exporter rejects checkpoint mismatches and fails unless the ONNX output
meets the configured numeric equivalence thresholds.
