# 演示材料

`agent_chat_demo.gif` 由真实 Android 截图生成，依次展示健康助手加载、SSE 增量输出和完成状态。它用于仓库预览，不代表性能基准；实际延迟应以自动化评测报告中的 P50/P95 数据为准。

重新生成：

```powershell
ffmpeg -y -f concat -safe 0 -i docs/demo/frames.txt -vf "fps=8,scale=360:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer" -loop 0 docs/demo/agent_chat_demo.gif
```

