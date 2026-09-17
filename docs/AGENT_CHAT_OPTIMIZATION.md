# Agent 对话优化说明

## 本轮目标

- Android 使用真正的 SSE 增量输出。
- 保存并复用 `conversation_id`。
- 网络重试不重复写入服务端对话记忆。
- 将今日指标、7 天逐日记录、睡眠、压力、症状和用户备注组合为 Agent 上下文。
- 流中断时不把半截助手回复写入长期记忆。
- 限制大模型对视频估计指标做过度医学推断。

## 请求契约

`POST /api/agent/chat` 新增：

- `client_message_id`：Android 每条用户消息生成一个 UUID；重试复用原 UUID。
- `weekly_records`：最近 7 条逐日记录。
- `user_context`：睡眠、主观压力、熬夜、咖啡、运动后状态和症状。
- `user_note`：用户保存的补充状态。

服务端以 `(conversation_id, client_message_id, role)` 建立唯一索引。同一消息重试时：

1. 已有完整助手回复：直接返回缓存回复。
2. 只有用户消息：继续生成，不重复写入用户消息。
3. 流式生成中断：不保存半截助手回复。

## Android 行为

- OkHttp 负责聊天 SSE，普通分析和周报仍使用原 JSON 请求。
- 收到 `done` 或 `error` 后立即结束流读取，不依赖 TCP 连接关闭。
- 页面保存最近 40 条消息，重新进入后恢复。
- 生成中发送按钮变为“停止”。
- 失败和手动停止后可用原 `client_message_id` 重新发送。
- 清空会话前二次确认，同时清理本地记录和服务端记忆。
- 输入框支持最多 3 行和 1000 个字符。

## 开发环境连接

Debug 构建使用：

```text
http://127.0.0.1:8100
```

连接真机前执行：

```powershell
adb reverse tcp:8100 tcp:8100
```

这样手机可以通过 USB 访问电脑后端，不依赖经常变化的局域网 IP。Release 构建当前仍使用
`https://api.example.com` 占位地址，正式发布前必须替换为实际可访问的 HTTPS 域名。

## 已验证

- 后端测试：8 项通过。
- Android 单元测试与 Debug APK 构建：通过。
- PC 两轮 SSE：通过，第二轮延续同一 `conversation_id`。
- 同一 `client_message_id` 重试：回复一致，没有重复记忆。
- Android 真机：已收到连续 `delta` 和 `done`，证明 OkHttp SSE 增量链路可用。
- Android instrumentation APK 已构建；MIUI 在手机锁屏时拒绝安装测试 APK，
  需要解锁后完成两轮自动化测试。

## 发布前事项

1. 使用 HTTPS 公网域名替换 Release 的局域网地址。
2. 将内存/SQLite 会话存储升级为带用户鉴权和过期策略的服务端存储。
3. 对提示词建立固定评测集，覆盖过度医学推断、数据不足、异常症状和提示注入。
4. 将 Android `AsyncTask` 迁移到 ViewModel 与协程或受生命周期管理的 Executor。
5. 解锁真机后执行两轮 instrumentation 测试并人工检查聊天页面布局。

## P1 Agent 编排升级

- Android 首次请求 `POST /api/auth/anonymous` 获取匿名设备令牌，后续请求统一携带
  Bearer Token；后端只保存令牌哈希。
- Android 在 Agent 调用前通过 `POST /api/health/records/sync` 同步结构化记录。
- 聊天正文不再重复发送 7 天原始记录，后端根据认证用户读取历史。
- 单 Agent 编排器执行意图识别，并依次调用健康记录、测量质量、趋势和风险工具。
- SSE `meta` 返回 `intent`、`tools_used` 和 `data_source`。
- 最近 12 条消息作为短期记忆；更早消息压缩为摘要并删除旧原文。
- 用户年龄段、运动习惯、关注方向、回答风格和基线天数使用结构化档案保存。
- 首页“我的”进入健康档案页，可编辑上述档案，并保留“隐私与数据管理”入口。
