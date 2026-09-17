# Third Party Notices

This repository includes or integrates third-party software and model artifacts.
Their licenses apply independently from the original code in this repository.

## rPPG-Toolbox and EfficientPhys

The Android ONNX asset `app/src/main/assets/models/efficientphys_pure.onnx`
was exported from an EfficientPhys checkpoint used with rPPG-Toolbox.
rPPG-Toolbox is distributed under the Responsible AI Source Code License.
The complete license text is included at
`licenses/rPPG-Toolbox-LICENSE.txt` and contains use restrictions that must be
reviewed before reuse or redistribution.

Upstream project: https://github.com/ubicomplab/rPPG-Toolbox

## MediaPipe

The project integrates MediaPipe Tasks Vision. The Face Landmarker model is
downloaded during the Android build from Google's official model URL declared
in `app/download_tasks.gradle`. MediaPipe source and samples are distributed
under the Apache License 2.0. A copy is included at
`licenses/Apache-2.0.txt`.

Upstream project: https://github.com/google-ai-edge/mediapipe

## ONNX Runtime

The Android app depends on Microsoft ONNX Runtime through Gradle. Refer to the
upstream project for its current license and notices.

Upstream project: https://github.com/microsoft/onnxruntime

## No Dataset Redistribution

The public repository intentionally excludes raw videos and derived per-frame
physiological signal CSV files. Evaluation summaries in source code and tests
are engineering examples and are not clinical ground truth.
