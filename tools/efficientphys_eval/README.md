# EfficientPhys Local Evaluation

This tool runs the unmodified rPPG-Toolbox EfficientPhys model on a local
video and compares its predicted waveform and FFT heart rate with a 60-sample
GREEN baseline.

The input video is processed locally and is not uploaded.

```powershell
python `
  tools\efficientphys_eval\evaluate_video.py `
  --video <path-to-test-video> `
  --toolbox <path-to-rPPG-Toolbox> `
  --checkpoint <path-to-EfficientPhys-checkpoint> `
  --output-dir docs\efficientphys_evaluation `
  --app-green-hr 45
```

The evaluator automatically tests all four video orientations before choosing
the most stable face detection. Use `--rotation 90_ccw` (or another explicit
value) to override this behavior.

Outputs:

- `evaluation.json`
- `efficientphys_bvp.csv`
- `green_baseline.csv`
- `bvp_green_comparison.png`

The generated SQI is an engineering quality score. It is not a medical
confidence value. The phone video has no synchronized contact PPG ground truth,
so the comparison does not establish accuracy.
