# EfficientPhys Android 端侧部署

## 部署状态

脉镜智康已加入 EfficientPhys FP16 ONNX CPU 推理链路。现有
GREEN+FFT 保留为对照和失败回退，不修改后端，不上传视频、裁剪人脸或 BVP。

## 模型

- 权重：`PURE_EfficientPhys.pth`
- SHA256：`E65A962E07BCAC32A668E6ACB9F8ED43CDB1B01CFB97262654DC5B55C0CF3A49`
- 训练数据集：PURE
- ONNX opset：17
- Android asset：`app/src/main/assets/models/efficientphys_pure.onnx`
- Asset SHA256：`456A877C37C8C1A1F277F2466F3DAF6A3A5569BACD347C7D0AB50247A294AE78`
- ONNX 文件大小：11,195,963 bytes
- 输入：Float32 `[180, 3, 72, 72]`，TCHW、RGB
- 输出：Float32 `[180]`，DiffNormalized BVP

导出 Wrapper 会复制输入末帧，再调用等价 ONNX 前向。原始
rPPG-Toolbox 模型源码未修改。

## Android 依赖

```toml
onnxruntime = "1.26.0"
onnxruntime-android = {
    module = "com.microsoft.onnxruntime:onnxruntime-android",
    version.ref = "onnxruntime"
}
```

模型由 `EfficientPhysEngine` 从 assets 加载。`OrtEnvironment` 和
`OrtSession` 在进程内单例复用，输入使用直接内存 `FloatBuffer`，推理在现有
`AsyncTask` 后台线程执行。

## 业务流程

1. Camera2 继续录制 15 秒前置摄像头 MP4。
2. 原 GREEN+FFT 链路计算传统对照、呼吸率和当前趋势字段。
3. 从视频中间选择约 6 秒稳定片段，按视频帧率提取 180 帧。
4. MediaPipe 定位完整人脸，使用 1.5 倍静态正方形框。
5. 构造并归一化 EfficientPhys TCHW 输入。
6. ONNX Runtime 在手机 CPU 执行 FP16 模型。
7. 恢复 BVP、计算 HR 和 SQI。
8. 模型输出有限且最低质量门槛通过时使用 EfficientPhys HR。
9. 初始化、解码、推理或质量检查失败时回退 GREEN+FFT。

状态填写页会显示 `EfficientPhys` 和 SQI，普通日志只记录最终指标和耗时，不记录
图像或逐帧 RGB。

## 主要代码

- `rppg/model/EfficientPhysEngine.java`
- `rppg/model/EfficientPhysMetadata.java`
- `rppg/model/RppgInferenceResult.java`
- `rppg/pipeline/EfficientPhysVideoAnalyzer.java`
- `rppg/signal/RppgSignalProcessor.java`

## 当前限制

- 第一版只部署固定单窗口模型，不是 CameraX 实时推理。
- 15 秒模式只将 EfficientPhys 用于 HR；当前呼吸率和 HRV 趋势字段仍来自旧链路。
- 模型只在一段手机视频上完成离线可行性检查，没有接触式 PPG 真值。
- 最终 Debug APK 已构建；真机运行烟雾测试受手机 PIN 锁屏和 MIUI USB 安装确认
  限制，需解锁手机后补测。

APK：`app/build/outputs/apk/debug/app-debug.apk`
