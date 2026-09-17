# EfficientPhys 部署测试报告

## Python 导出验证

| 项目 | 结果 |
|---|---:|
| Checkpoint key 数 | 21 |
| `load_state_dict(strict=True)` | 通过 |
| ONNX Wrapper 与原始 PyTorch 最大误差 | 0 |
| FP32 ONNX 最大绝对误差 | 0.000000775 |
| FP32 ONNX 平均绝对误差 | 0.000000106 |
| FP32 Pearson | 0.999999999999 |
| FP16 ONNX 最大绝对误差 | 0.00372028 |
| FP16 ONNX 平均绝对误差 | 0.00026639 |
| FP16 Pearson | 0.999991598 |
| NaN/Inf | 0 |

FP16 通过部署门槛：最大误差小于 0.02，Pearson 大于 0.995，输出形状 `[180]`。

## Android 测试

- `:app:compileDebugJavaWithJavac`：通过
- `testDebugUnitTest`：通过
- 合成 72 BPM 差分波形 HR 恢复：通过
- NaN 输出拒绝：通过
- `HealthData` 模型诊断字段：通过
- `clean testDebugUnitTest assembleDebug`：通过

最终 Debug APK 已安装到 `chenfeng`（arm64-v8a）真机，debug-only 烟雾测试通过：

```text
SMOKE_PASS model=EfficientPhys version=PURE-rPPG-Toolbox-FP16-v1
samples=180 finite=true initMs=190 inferenceMs=511
```

2026-07-23 使用 App 完整录制 15 秒人脸视频并执行端到端分析：

| 项目 | 真机结果 |
|---|---:|
| 视频文件大小 | 23,512,832 bytes |
| 分析页开始到结果生成 | 约 79.3 秒 |
| EfficientPhys 推理 | 359 ms |
| 模型估计心率 | 49 bpm |
| SQI | 44.3 |
| 有效人脸帧比例 | 100% |
| 模型输入采样率 | 30.00 FPS |
| 页面跳转 | 分析页 → 状态填写页，成功 |
| OOM、NaN/Inf、ORT 初始化错误 | 未发现 |

本次结果未回退到 GREEN+FFT。随后状态填写页可进入报告页，报告页可返回首页，
周报页也能正常打开。49 bpm 未与同步指夹式 PPG 对照，不能作为精度结论；
SQI 44.3 仅表示当前工程质量门槛下可用，仍需继续改善目标域信号质量。

## 产物检查

| 项目 | 结果 |
|---|---:|
| Debug APK | 128,578,157 bytes |
| FP16 ONNX asset | 11,195,963 bytes |
| 模型 ZIP 存储方式 | 未压缩 |
| Asset 与导出文件 SHA256 | 一致 |
| arm64-v8a ORT native library | 27,408,600 bytes |
| armeabi-v7a ORT native library | 19,502,724 bytes |

Windows ONNX Runtime CPU 的 180 帧 FP16 单窗口测试：

- 平均：271.2 ms
- P50：270.6 ms
- P95：278.4 ms

Windows 数据仅用于 ONNX 导出回归；本次 Android 单窗口模型推理为 359 ms，
debug-only 合成输入冷启动推理为 511 ms。

## APK

`app/build/outputs/apk/debug/app-debug.apk`

## 后续验收

1. 使用同步指夹式 PPG 建立心率、BVP 波形和 HRV 的真值对照。
2. 在不同肤色、光照、机型、运动幅度和心率范围下建立目标域测试集。
3. 优化视频解码与人脸检测流程；当前约 79 秒总分析时间主要消耗在逐帧解码和 MediaPipe。
4. 在目标域完成验证或微调后，再决定是否发布模型测量结果。
