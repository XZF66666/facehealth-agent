# FaceHealth Agent Backend

FaceHealth Agent Android App 的独立 FastAPI 后端。服务负责健康规则判断、模型调用、
多轮对话记忆和 SSE 流式输出。原始人脸视频不应发送到本服务。

P1 版本增加了确定性 Agent 工具编排、匿名设备令牌、服务端健康记录、用户档案，
以及“短期消息 + 长期摘要”的分层记忆。大模型只负责解释工具结果，不负责计算趋势。

## 1. 使用 VS Code 打开

在 VS Code 中选择“文件 -> 打开文件夹”，打开仓库中的：

```text
backend/
```

项目已经包含 `.vscode/launch.json`。创建虚拟环境并安装依赖后，可按 `F5` 启动。

## 2. 初始化

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
Copy-Item .env.example .env
```

在 `.env` 中填写服务端模型配置：

```env
LLM_API_KEY=
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=gpt-4.1-mini
AUTH_REQUIRED=false
```

也可以配置任何兼容 `/v1/chat/completions` 的模型服务。未填写密钥时，三个接口仍会
使用本地规则与模板返回结果，便于先完成 Android 联调。

## 3. 启动

```powershell
uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload
```

打开接口文档：

```text
http://127.0.0.1:8100/docs
```

健康检查：

```text
http://127.0.0.1:8100/health
```

## 4. API

- `POST /api/agent/analyze`：今日指标、用户状态和一周摘要分析。
- `POST /api/agent/weekly-report`：最近记录与一周趋势报告。
- `POST /api/agent/chat`：普通 JSON 对话；请求传入 `stream=true` 时返回 SSE。
- `DELETE /api/agent/chat/{conversation_id}`：清空指定会话记忆。
- `POST /api/auth/anonymous`：为 Android 设备签发匿名访问令牌。
- `POST /api/health/records/sync`：同步本地结构化健康记录。
- `GET /api/health/records`：读取当前用户的服务端历史记录。
- `GET/PUT /api/health/profile`：读取或更新用户档案与回答偏好。

聊天 Agent 通过 OpenAI-compatible Function Calling 选择候选工具，Tool Registry
对工具执行白名单校验、数量限制和依赖排序。`HealthRecordTool`、
`MeasurementQualityTool` 与 `RiskTool` 是不能被模型绕过的安全工具；趋势场景按需执行
`TrendTool`，随后由大模型解释确定性结果。响应和 SSE `meta` 包含 `intent`、
`tools_used`、`planning_mode`、`tool_trace` 与 `data_source`，用于审计和前端展示。

模型规划失败时自动使用确定性工作流，不影响风险筛查。工具不接受模型生成的健康指标，
而是使用认证用户的服务端数据或经过校验的兼容请求数据。

工具规划采用最多 3 轮的 `Plan -> Execute -> Observe -> Re-plan` 循环，每轮只执行尚未
执行的白名单工具。`plan`、`observe` 和 `complete` 状态保存在 `agent_checkpoint` 表中，
响应通过 `run_id` 和 `planning_iterations` 暴露执行标识与实际规划轮数。

配置百炼 OpenAI 兼容地址、模型和 API Key 后，可执行真实 Function Calling 冒烟测试：

```powershell
python tools/verify_qwen_tool_calling.py
```

当前请求字段与旧版 Android 保持兼容。已认证设备存在服务端记录时，Agent 优先
使用服务端数据；请求体中的 `user_id` 和 7 天历史不再作为可信数据源。

## 5. 真机联调

手机和电脑连接同一局域网。将 `<LAN_IP>` 替换为电脑在当前网络中的地址：

```text
http://<LAN_IP>:8100
```

Android 模拟器使用 `http://10.0.2.2:8100`。联调确认设备令牌正常后，将 `.env`
中的 `AUTH_REQUIRED` 改为 `true`。正式环境还必须使用 HTTPS、限制 CORS 并增加
请求限流。

## 6. 记忆策略

- 最近 12 条消息作为短期上下文。
- 超出窗口的旧消息会压缩成不含精确健康数值的会话摘要，随后删除旧原文。
- 年龄段、运动习惯、关注方向和回答长度偏好保存在结构化用户档案中。
- 健康指标保存在 `user_health_record` 表中，不写入长期对话摘要。

## 7. 测试

```powershell
pytest
```

运行确定性 Agent 评测并生成 Markdown/JSON 报告：

```powershell
python -m evals.run
```

评测数据位于 `evals/datasets/`，最新报告位于 `evals/reports/`。当前评测只覆盖可重复验证的意图、工具工作流、质量门控、趋势和风险规则，不将其包装为 LLM 生成质量或临床准确性指标。
