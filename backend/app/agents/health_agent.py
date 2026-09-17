from collections.abc import AsyncIterator
import re
from uuid import uuid4

from app.agents.context_builder import (
    build_analyze_messages,
    build_chat_messages,
    build_weekly_messages,
)
from app.agents.orchestrator import AgentOrchestrator, PreparedChat
from app.schemas.agent import AnalyzeRequest, ChatRequest, WeeklyReportRequest
from app.schemas.health import RuleAssessment, UserContext
from app.services.llm_client import LlmClientError, OpenAICompatibleLlmClient
from app.services.memory_service import ConversationStore
from app.services.rule_engine import HealthRuleEngine


DEFAULT_WARNING = (
    "本结果用于个人健康管理，不作为医学诊断依据。如出现胸痛、明显胸闷、"
    "呼吸困难、晕厥、出冷汗或持续心悸，请及时就医。"
)


class HealthAgent:
    def __init__(
        self,
        rules: HealthRuleEngine,
        llm: OpenAICompatibleLlmClient,
        memory: ConversationStore,
        orchestrator: AgentOrchestrator,
    ) -> None:
        self.rules = rules
        self.llm = llm
        self.memory = memory
        self.orchestrator = orchestrator

    async def analyze(self, request: AnalyzeRequest, request_id: str) -> dict:
        assessment = self.rules.assess(
            request.today_metrics, request.user_context, request.weekly_summary
        )
        result = self._fallback_analysis(request, assessment)
        model_used = False
        if self.llm.configured:
            try:
                generated = await self.llm.complete_json(
                    build_analyze_messages(request, assessment)
                )
                result.update(self._string_fields(generated, result.keys()))
                model_used = True
            except LlmClientError:
                pass
        result["risk_warning"] = self._enforce_warning(
            result.get("risk_warning", ""), assessment
        )
        return {
            "request_id": request_id,
            "risk_level": assessment.risk_level,
            "rule_flags": assessment.flags,
            **result,
            "model_used": model_used,
        }

    async def weekly_report(self, request: WeeklyReportRequest, request_id: str) -> dict:
        assessments = [
            self.rules.assess(record, UserContext(), request.weekly_summary)
            for record in request.weekly_records
        ]
        overall = self._highest_assessment(assessments)
        result = self._fallback_weekly(request, assessments)
        model_used = False
        if self.llm.configured:
            try:
                generated = await self.llm.complete_json(
                    build_weekly_messages(request, assessments)
                )
                if isinstance(generated.get("weekly_report"), str):
                    result["weekly_report"] = generated["weekly_report"]
                for key in ("key_findings", "suggestions"):
                    if isinstance(generated.get(key), list):
                        result[key] = [str(item) for item in generated[key]][:8]
                if isinstance(generated.get("risk_warning"), str):
                    result["risk_warning"] = generated["risk_warning"]
                model_used = True
            except LlmClientError:
                pass
        result["risk_warning"] = self._enforce_warning(
            result.get("risk_warning", ""), overall
        )
        return {
            "request_id": request_id,
            "risk_level": overall.risk_level,
            **result,
            "model_used": model_used,
        }

    async def prepare_chat(self, request: ChatRequest, user_id: str) -> PreparedChat:
        planning_mode = "deterministic_fallback"
        tool_planner = None
        if self.llm.configured:
            planning_mode = "llm_tool_calling"

            async def tool_planner(schemas: list[dict], observations: dict) -> list[str]:
                return await self.llm.select_tools(
                    [
                        {
                            "role": "system",
                            "content": (
                                "你是健康管理 Agent 的工具规划器。只选择完成用户问题所需的工具，"
                                "不要自行计算健康指标。服务端会强制执行身份、质量和风险策略。"
                            ),
                        },
                        {
                            "role": "user",
                            "content": (
                                request.message
                                + "\n\n已执行步骤与观察结果："
                                + str(observations)
                                + "\n请选择下一步尚未执行且确有必要的工具；无需更多工具时不要调用工具。"
                            ),
                        },
                    ],
                    schemas,
                )
        return await self.orchestrator.prepare(
            request,
            user_id,
            planning_mode=planning_mode,
            tool_planner=tool_planner,
        )

    async def chat(
        self,
        request: ChatRequest,
        request_id: str,
        prepared: PreparedChat | None = None,
    ) -> dict:
        conversation_id = request.conversation_id or uuid4().hex
        prepared = prepared or await self.prepare_chat(request, request.user_id)
        assessment = prepared.assessment
        cached_reply = await self.memory.find_message(
            conversation_id,
            request.client_message_id,
            "assistant",
            request.user_id,
        )
        if cached_reply is not None:
            return {
                "request_id": request_id,
                "conversation_id": conversation_id,
                "reply": cached_reply,
                "risk_level": assessment.risk_level,
                "rule_flags": assessment.flags,
                "model_used": self.llm.configured,
                "intent": prepared.intent,
                "tools_used": prepared.tools_used,
                "data_source": prepared.data_source,
                "planning_mode": prepared.planning_mode,
                "tool_trace": prepared.tool_trace,
                "run_id": prepared.run_id,
                "planning_iterations": prepared.planning_iterations,
                "knowledge_sources": prepared.knowledge_sources,
            }
        conversation_summary, history = await self.memory.context(
            conversation_id, request.user_id
        )
        user_exists = (
            await self.memory.find_message(
                conversation_id,
                request.client_message_id,
                "user",
                request.user_id,
            )
            is not None
        )
        messages = build_chat_messages(
            request,
            prepared,
            history,
            conversation_summary,
            include_current_user=not user_exists,
        )
        if not user_exists:
            await self.memory.append(
                conversation_id,
                request.user_id,
                "user",
                request.message,
                request.client_message_id,
            )
        model_used = False
        if not prepared.quality["can_interpret"]:
            reply = self._quality_gate_reply(prepared)
        elif self.llm.configured:
            try:
                reply = await self.llm.complete(messages)
                model_used = True
            except LlmClientError:
                reply = self._fallback_chat(prepared.today_metrics, assessment)
        else:
            reply = self._fallback_chat(prepared.today_metrics, assessment)
        reply = self._append_forced_warning(reply, assessment)
        await self.memory.append(
            conversation_id,
            request.user_id,
            "assistant",
            reply,
            request.client_message_id,
        )
        await self.memory.compact(
            conversation_id, request.user_id, self._summarize_memory
        )
        return {
            "request_id": request_id,
            "conversation_id": conversation_id,
            "reply": reply,
            "risk_level": assessment.risk_level,
            "rule_flags": assessment.flags,
            "model_used": model_used,
            "intent": prepared.intent,
            "tools_used": prepared.tools_used,
            "data_source": prepared.data_source,
            "planning_mode": prepared.planning_mode,
            "tool_trace": prepared.tool_trace,
            "run_id": prepared.run_id,
            "planning_iterations": prepared.planning_iterations,
            "knowledge_sources": prepared.knowledge_sources,
        }

    async def stream_chat(
        self,
        request: ChatRequest,
        conversation_id: str,
        prepared: PreparedChat | None = None,
    ) -> AsyncIterator[str]:
        prepared = prepared or await self.prepare_chat(request, request.user_id)
        assessment = prepared.assessment
        cached_reply = await self.memory.find_message(
            conversation_id,
            request.client_message_id,
            "assistant",
            request.user_id,
        )
        if cached_reply is not None:
            async for chunk in self._chunk_text(cached_reply):
                yield chunk
            return
        conversation_summary, history = await self.memory.context(
            conversation_id, request.user_id
        )
        user_exists = (
            await self.memory.find_message(
                conversation_id,
                request.client_message_id,
                "user",
                request.user_id,
            )
            is not None
        )
        messages = build_chat_messages(
            request,
            prepared,
            history,
            conversation_summary,
            include_current_user=not user_exists,
        )
        if not user_exists:
            await self.memory.append(
                conversation_id,
                request.user_id,
                "user",
                request.message,
                request.client_message_id,
            )
        chunks: list[str] = []
        if not prepared.quality["can_interpret"]:
            async for chunk in self._chunk_text(self._quality_gate_reply(prepared)):
                chunks.append(chunk)
                yield chunk
        elif self.llm.configured:
            try:
                async for chunk in self.llm.stream(messages):
                    chunks.append(chunk)
                    yield chunk
            except LlmClientError:
                if not chunks:
                    async for chunk in self._chunk_text(
                        self._fallback_chat(prepared.today_metrics, assessment)
                    ):
                        chunks.append(chunk)
                        yield chunk
                else:
                    raise
        else:
            async for chunk in self._chunk_text(
                self._fallback_chat(prepared.today_metrics, assessment)
            ):
                chunks.append(chunk)
                yield chunk

        warning = assessment.forced_warning
        if warning and warning not in "".join(chunks):
            warning_chunk = "\n\n风险提示：" + warning
            chunks.append(warning_chunk)
            yield warning_chunk
        await self.memory.append(
            conversation_id,
            request.user_id,
            "assistant",
            "".join(chunks),
            request.client_message_id,
        )
        await self.memory.compact(
            conversation_id, request.user_id, self._summarize_memory
        )

    def _fallback_analysis(
        self, request: AnalyzeRequest, assessment: RuleAssessment
    ) -> dict[str, str]:
        status = "；".join(assessment.findings)
        context = request.user_context
        reasons = []
        if context.sleep_hours and context.sleep_hours < 6:
            reasons.append("睡眠时长不足")
        if context.is_late_sleep:
            reasons.append("近期熬夜")
        if context.is_coffee:
            reasons.append("咖啡因摄入")
        if context.is_after_exercise:
            reasons.append("运动后测量")
        if context.stress_level in {"较高", "高"}:
            reasons.append("主观压力较高")
        return {
            "today_status": status,
            "weekly_trend": request.weekly_summary.trend,
            "possible_reason": "、".join(reasons) if reasons else "暂未发现明确生活方式诱因",
            "suggestion": self._suggestion_for(assessment),
            "risk_warning": DEFAULT_WARNING,
        }

    def _fallback_weekly(
        self, request: WeeklyReportRequest, assessments: list[RuleAssessment]
    ) -> dict:
        summary = request.weekly_summary
        findings = []
        if not request.weekly_records:
            findings.append("近 7 天暂无检测记录")
        else:
            findings.extend(
                [finding for item in assessments for finding in item.findings if "未触发" not in finding]
            )
        findings = list(dict.fromkeys(findings))[:8] or ["本周指标整体平稳"]
        report = (
            f"本周共记录 {len(request.weekly_records)} 次检测。平均心率 "
            f"{round(summary.avg_heart_rate)} 次/分，平均 HRV {round(summary.avg_hrv)} ms。"
            f"趋势判断：{summary.trend}。"
        )
        return {
            "weekly_report": report,
            "key_findings": findings,
            "suggestions": ["保持规律睡眠和稳定测量时间", "压力偏高时优先选择低强度活动", "异常结果可在静息后复测"],
            "risk_warning": DEFAULT_WARNING,
        }

    def _fallback_chat(
        self, today_metrics, assessment: RuleAssessment
    ) -> str:
        if today_metrics is None:
            return "今天还没有有效检测数据。建议先完成一次本地检测，再结合趋势给出个性化建议。"
        return (
            "根据今天的指标和近期趋势，"
            + "；".join(assessment.findings)
            + "。"
            + self._suggestion_for(assessment)
        )

    def _suggestion_for(self, assessment: RuleAssessment) -> str:
        if assessment.risk_level == "urgent":
            return "先停止运动并关注症状变化，必要时及时就医。"
        if assessment.risk_level == "attention":
            return "今天建议降低运动强度、补水休息，并在静息后复测。"
        if assessment.risk_level == "observe":
            return "建议保持规律作息，避免熬夜，并继续观察后续趋势。"
        return "当前可维持日常活动，继续保持规律睡眠和稳定测量。"

    def _highest_assessment(self, assessments: list[RuleAssessment]) -> RuleAssessment:
        if not assessments:
            return RuleAssessment(risk_level="insufficient_data", flags=["NO_WEEKLY_DATA"])
        order = {"insufficient_data": 0, "normal": 1, "observe": 2, "attention": 3, "urgent": 4}
        return max(assessments, key=lambda item: order.get(item.risk_level, 0))

    def _enforce_warning(self, warning: str, assessment: RuleAssessment) -> str:
        if assessment.forced_warning:
            return assessment.forced_warning
        return warning.strip() or DEFAULT_WARNING

    def _append_forced_warning(self, reply: str, assessment: RuleAssessment) -> str:
        if assessment.forced_warning and assessment.forced_warning not in reply:
            return reply.rstrip() + "\n\n风险提示：" + assessment.forced_warning
        return reply

    def _string_fields(self, source: dict, allowed_keys) -> dict[str, str]:
        return {
            key: value
            for key, value in source.items()
            if key in allowed_keys and isinstance(value, str) and value.strip()
        }

    def _quality_gate_reply(self, prepared: PreparedChat) -> str:
        return (
            prepared.quality["reason"]
            + "。请在光线均匀、身体静止、人脸完整入框的条件下重新检测，"
            "本次结果暂不用于趋势或运动建议。"
        )

    async def _summarize_memory(
        self, previous_summary: str, messages: list[dict[str, str]]
    ) -> str:
        user_topics = [
            re.sub(r"\d+(?:\.\d+)?", "某项数值", item["content"]).strip()
            for item in messages
            if item["role"] == "user" and item["content"].strip()
        ]
        parts = [previous_summary.strip()] if previous_summary.strip() else []
        if user_topics:
            parts.append("用户近期关注：" + "；".join(user_topics[-6:]))
        return "\n".join(parts)[-3000:]

    async def _chunk_text(self, text: str) -> AsyncIterator[str]:
        step = 6
        for index in range(0, len(text), step):
            yield text[index : index + step]
