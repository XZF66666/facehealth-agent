# EfficientPhys 预处理规范

## 训练配置依据

配置来自 rPPG-Toolbox：

`configs/infer_configs/PURE_UBFC-rPPG_EFFICIENTPHYS.yaml`

- RGB
- 72x72
- `Standardized`
- `DiffNormalized` 标签
- 180 帧 chunk
- Haar 静态人脸框
- 1.5 倍扩大框
- `FRAME_DEPTH=10`
- 30 FPS

## Android 输入构造

1. 读取视频元数据帧率，限制异常值到 15-60 FPS。
2. 以视频中点为中心选择 `(180-1)/fps` 秒片段。
3. 使用真实时间戳提取 180 帧，不把 15 秒强制压缩成 180 帧。
4. MediaPipe Face Landmarker 使用 IMAGE 模式检测人脸。
5. 根据全部 landmarks 求外接框，以中心扩为 1.5 倍正方形并裁剪边界。
6. 静态框用于完整 180 帧；每 15 帧重新检测一次以计算有效人脸比例。
7. 双线性缩放至 72x72。
8. Android ARGB 像素按 RGB 顺序写入 TCHW：
   `[time][channel][height][width]`。
9. 像素初值为 0-255 Float32。
10. 对单窗口全部 RGB 像素计算一个全局均值和标准差：

```text
standardized = (value - global_mean) / global_std
```

11. ONNX Wrapper 在内部复制第 180 帧作为末帧。
12. 模型内部执行相邻帧差分，输出 180 个 DiffNormalized BVP 样本。

## 后处理

1. 对差分输出累计求和。
2. 使用 `lambda=100` 平滑先验去趋势。
3. 前后向一阶高通和低通形成 0.75-2.5 Hz 零相位近似带通。
4. 去均值并标准化。
5. 在 42-180 BPM 搜索频域主峰。
6. 使用最小峰间距和局部峰值计算峰间 HR。
7. 两种 HR 接近时取平均，否则使用频域 HR。

## SQI

0-100 工程质量分综合：

- 频谱主峰占比：35%
- FFT/峰间 HR 一致性：25%
- 有效人脸检查比例：20%
- 实际帧率：10%
- 曝光稳定性：10%

等级：

- 80-100：优秀
- 60-79：可用
- 40-59：偏低
- 0-39：无效

首版最低运行门槛为 20 分，低于门槛或出现 NaN/Inf 时回退 GREEN+FFT。该 SQI
尚未使用接触式 PPG 数据标定，不是医学置信度。
