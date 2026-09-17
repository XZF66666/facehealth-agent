from dataclasses import dataclass
from datetime import date
from statistics import mean
from collections.abc import Awaitable, Callable
from uuid import uuid4

from app.schemas.agent import ChatRequest
from app.schemas.health import (
    HealthMetrics,
    RuleAssessment,
    UserContext,
    WeeklySummary,
)
from app.schemas.user_data import HealthRecordInput, UserProfile
from app.services.health_record_service import HealthRecordStore
from app.services.rule_engine import HealthRuleEngine
from app.agents.tool_registry import ToolRegistry
from app.services.checkpoint_service import AgentCheckpointStore
from app.services.knowledge_retriever import KnowledgeRetriever
from pathlib import Path


ToolPlanner = Callable[[list[dict], dict], Awaitable[list[str]]]


class IntentRouter:
    EMERGENCY_TERMS = (
        "胸痛",
        "胸闷",
        "呼吸困难",
        "喘不上气",
        "晕厥",
        "昏倒",
        "持续心悸",
    )
    TREND_TERMS = ("趋势", "本周", "最近", "变化", "对比", "平均")
    QUALITY_TERMS = (
        "准确",
        "可信",
        "质量",
        "误差",
        "重测",
        "测得准",
        "摄像头测量",
    )
    TODAY_TERMS = ("今天", "现在", "当前", "运动", "恢复", "疲劳", "压力")

    def route(self, message: str) -> str:
        normalized = message.strip().lower()
        if any(term in normalized for term in self.EMERGENCY_TERMS):
            return "urgent_symptom"
        if any(term in normalized for term in self.QUALITY_TERMS):
            return "measurement_quality"
        if any(term in normalized for term in self.TREND_TERMS):
            return "weekly_trend"
        if any(term in normalized for term in self.TODAY_TERMS):
            return "today_status"
        return "health_education"


class HealthRecordTool:
    def __init__(self, store: HealthRecordStore) -> None:
        self.store = store

    async def load(
        self, user_id: str, request: ChatRequest, baseline_days: int
    ) -> tuple[list[HealthRecordInput], str]:
        stored = await self.store.recent(user_id, baseline_days)
        if stored:
            return stored, "server"

        fallback: list[HealthRecordInput] = []
        for index, metrics in enumerate(request.weekly_records):
            fallback.append(
                HealthRecordInput(
                    client_record_id=f"legacy-weekly-{index}",
                    date=metrics.date or f"legacy-{index:02d}",
                    metrics=metrics,
                )
            )
        if request.today_metrics is not None:
            fallback.append(
                HealthRecordInput(
                    client_record_id="legacy-today",
                    date=request.today_metrics.date or date.today().isoformat(),
                    metrics=request.today_metrics,
                    context=request.user_context,
                    user_note=request.user_note,
                )
            )
        return fallback, "request_fallback"


class MeasurementQualityTool:
    GOOD = {"合格", "良好", "优秀", "good", "excellent", "pass"}
    POOR = {"差", "不合格", "poor", "invalid", "failed"}

    def evaluate(self, metrics: HealthMetrics | None) -> dict:
        if metrics is None:
            return {
                "status": "missing",
                "can_interpret": False,
                "reason": "没有可用的生理信号记录",
            }
        quality = metrics.video_quality.strip().lower()
        if quality in self.POOR:
            return {
                "status": "poor",
                "can_interpret": False,
                "reason": "视频或信号质量不合格，应先重新检测",
            }
        if quality not in self.GOOD:
            return {
                "status": "unknown",
                "can_interpret": True,
                "reason": "缺少完整 SQI，结果只能作为趋势参考",
            }
        return {
            "status": "good",
            "can_interpret": True,
            "reason": "本次基础质量检查通过",
        }


class TrendTool:
    def analyze(
        self, records: list[HealthRecordInput]
    ) -> tuple[WeeklySummary, dict]:
        recent = records[-7:]
        if not recent:
            return WeeklySummary(), {"sample_count": 0, "directions": {}}

        metrics = [item.metrics for item in recent]
        summary = WeeklySummary(
            avg_heart_rate=round(mean(item.heart_rate for item in metrics), 1),
            avg_respiratory_rate=round(
                mean(item.respiratory_rate for item in metrics), 1
            ),
            avg_hrv=round(mean(item.hrv for item in metrics), 1),
            avg_stress_score=round(mean(item.stress_score for item in metrics), 1),
            avg_fatigue_score=round(mean(item.fatigue_score for item in metrics), 1),
            abnormal_days=sum(
                item.stress_score >= 70
                or item.fatigue_score >= 70
                or item.heart_rate > 100
                for item in metrics
            ),
        )
        directions: dict[str, dict] = {}
        if len(metrics) >= 2:
            for name in (
                "heart_rate",
                "respiratory_rate",
                "hrv",
                "stress_score",
                "fatigue_score",
            ):
                first = float(getattr(metrics[0], name))
                last = float(getattr(metrics[-1], name))
                delta = round(last - first, 1)
                directions[name] = {
                    "first": first,
                    "last": last,
                    "delta": delta,
                    "direction": "up" if delta > 0 else "down" if delta < 0 else "stable",
                }
        summary.trend = self._trend_text(directions, len(metrics))
        return summary, {
            "sample_count": len(metrics),
            "date_range": [recent[0].date, recent[-1].date],
            "directions": directions,
        }

    @staticmethod
    def _trend_text(directions: dict[str, dict], sample_count: int) -> str:
        if sample_count < 2:
            return "记录不足，暂不能判断趋势"
        stress = directions.get("stress_score", {}).get("direction")
        fatigue = directions.get("fatigue_score", {}).get("direction")
        hrv = directions.get("hrv", {}).get("direction")
        if stress == "up" or fatigue == "up" or hrv == "down":
            return "恢复相关指标存在需要关注的变化"
        if stress == "down" and fatigue == "down" and hrv == "up":
            return "恢复相关指标整体改善"
        return "近期指标整体波动不大"


class RiskTool:
    def __init__(self, rules: HealthRuleEngine) -> None:
        self.rules = rules

    def evaluate(
        self,
        metrics: HealthMetrics | None,
        context: UserContext,
        weekly: WeeklySummary,
    ) -> RuleAssessment:
        return self.rules.assess(metrics, context, weekly)


@dataclass
class PreparedChat:
    intent: str
    tools_used: list[str]
    data_source: str
    today_metrics: HealthMetrics | None
    weekly_records: list[HealthMetrics]
    weekly_summary: WeeklySummary
    user_context: UserContext
    user_note: str
    profile: UserProfile
    quality: dict
    trend: dict
    assessment: RuleAssessment
    planning_mode: str
    tool_trace: list[dict]
    run_id: str
    planning_iterations: int
    knowledge_sources: list[dict]

    def prompt_context(self) -> dict:
        return {
            "intent": self.intent,
            "tools_used": self.tools_used,
            "data_source": self.data_source,
            "measurement_quality": self.quality,
            "trend_analysis": self.trend,
            "risk_assessment": self.assessment.model_dump(mode="json"),
            "profile": self.profile.model_dump(mode="json"),
            "planning_mode": self.planning_mode,
            "tool_trace": self.tool_trace,
            "run_id": self.run_id,
            "planning_iterations": self.planning_iterations,
            "knowledge_sources": self.knowledge_sources,
        }


class AgentOrchestrator:
    def __init__(
        self,
        records: HealthRecordStore,
        rules: HealthRuleEngine,
        checkpoints: AgentCheckpointStore | None = None,
        max_planning_iterations: int = 3,
    ) -> None:
        self.records = records
        self.intent_router = IntentRouter()
        self.health_records = HealthRecordTool(records)
        self.quality = MeasurementQualityTool()
        self.trends = TrendTool()
        self.risk = RiskTool(rules)
        self.registry = ToolRegistry.health_tools()
        self.checkpoints = checkpoints
        self.max_planning_iterations = max_planning_iterations
        self.knowledge = KnowledgeRetriever(
            Path(__file__).resolve().parents[1] / "knowledge" / "health_guidance.jsonl"
        )

    @property
    def tool_schemas(self) -> list[dict]:
        return self.registry.schemas()

    async def prepare(
        self,
        request: ChatRequest,
        user_id: str,
        requested_tools: list[str] | None = None,
        planning_mode: str = "deterministic_fallback",
        tool_planner: ToolPlanner | None = None,
    ) -> PreparedChat:
        run_id = request.client_message_id or uuid4().hex
        intent = self.intent_router.route(request.message)
        default_tools = ["TrendTool"] if intent == "weekly_trend" else []
        if intent == "health_education":
            default_tools.append("KnowledgeRetrievalTool")
        plan = self.registry.resolve(
            requested_tools if requested_tools is not None else default_tools,
            planning_mode,
            policy_required=["TrendTool"] if intent == "weekly_trend" else [],
        )
        trace: list[dict] = []
        planning_iterations = 0
        profile = await self.records.get_profile(user_id)
        stored, source = await self.health_records.load(
            user_id, request, profile.baseline_days
        )
        trace.append({"tool": "HealthRecordTool", "status": "success", "source": source})
        await self._checkpoint(run_id, user_id, 0, "observe", trace, intent)
        latest = stored[-1] if stored else None
        today = latest.metrics if latest else request.today_metrics
        context = latest.context if latest else request.user_context
        user_note = latest.user_note if latest else request.user_note
        quality = self.quality.evaluate(today)
        trace.append({"tool": "MeasurementQualityTool", "status": "success", "result": quality["status"]})
        await self._checkpoint(run_id, user_id, 1, "observe", trace, intent)
        weekly_summary = WeeklySummary()
        trend = {"sample_count": 0, "directions": {}}
        knowledge_sources: list[dict] = []
        dynamic_tools = set(plan.selected)
        planner_failed = False
        if tool_planner is not None:
            observations = {
                "intent": intent,
                "record_count": len(stored),
                "measurement_quality": quality["status"],
                "executed_tools": [item["tool"] for item in trace],
            }
            for iteration in range(1, self.max_planning_iterations + 1):
                planning_iterations = iteration
                try:
                    candidates = await tool_planner(self.tool_schemas, observations)
                except Exception:
                    planning_mode = "deterministic_fallback"
                    planner_failed = True
                    break
                allowed = self.registry.resolve(candidates, "llm_tool_calling").selected
                next_tools = [
                    name
                    for name in allowed
                    if name not in observations["executed_tools"]
                    and name in {"TrendTool", "KnowledgeRetrievalTool"}
                ][:1]
                await self._checkpoint(
                    run_id,
                    user_id,
                    2 + (iteration - 1) * 2,
                    "plan",
                    trace,
                    intent,
                    {"iteration": iteration, "requested": candidates, "accepted": next_tools},
                )
                if not next_tools:
                    break
                dynamic_tools.update(next_tools)
                if "TrendTool" in next_tools:
                    weekly_summary, trend = self.trends.analyze(stored)
                    trace.append(
                        {"tool": "TrendTool", "status": "success", "sample_count": trend["sample_count"]}
                    )
                    observations["executed_tools"].append("TrendTool")
                    observations["last_observation"] = {
                        "tool": "TrendTool",
                        "sample_count": trend["sample_count"],
                        "trend": weekly_summary.trend,
                    }
                    await self._checkpoint(
                        run_id,
                        user_id,
                        3 + (iteration - 1) * 2,
                        "observe",
                        trace,
                        intent,
                        {"iteration": iteration, "observation": observations["last_observation"]},
                    )
                if "KnowledgeRetrievalTool" in next_tools:
                    knowledge_sources = self.knowledge.search(request.message)
                    trace.append(
                        {"tool": "KnowledgeRetrievalTool", "status": "success", "result_count": len(knowledge_sources)}
                    )
                    observations["executed_tools"].append("KnowledgeRetrievalTool")
                    observations["last_observation"] = {
                        "tool": "KnowledgeRetrievalTool",
                        "result_count": len(knowledge_sources),
                        "sources": [item["source"] for item in knowledge_sources],
                    }
        if (tool_planner is None or planner_failed) and "TrendTool" in dynamic_tools and not any(
            item["tool"] == "TrendTool" for item in trace
        ):
            weekly_summary, trend = self.trends.analyze(stored)
            trace.append({"tool": "TrendTool", "status": "success", "sample_count": trend["sample_count"]})
        if (tool_planner is None or planner_failed) and "KnowledgeRetrievalTool" in dynamic_tools:
            knowledge_sources = self.knowledge.search(request.message)
            trace.append({"tool": "KnowledgeRetrievalTool", "status": "success", "result_count": len(knowledge_sources)})
        if not stored:
            weekly_summary = request.weekly_summary
        assessment = self.risk.evaluate(today, context, weekly_summary)
        trace.append({"tool": "RiskTool", "status": "success", "result": assessment.risk_level})
        await self._checkpoint(
            run_id,
            user_id,
            2 + self.max_planning_iterations * 2,
            "complete",
            trace,
            intent,
        )
        return PreparedChat(
            intent=intent,
            tools_used=[item["tool"] for item in trace],
            data_source=source,
            today_metrics=today,
            weekly_records=[item.metrics for item in stored[-7:]],
            weekly_summary=weekly_summary,
            user_context=context,
            user_note=user_note,
            profile=profile,
            quality=quality,
            trend=trend,
            assessment=assessment,
            planning_mode=planning_mode,
            tool_trace=trace,
            run_id=run_id,
            planning_iterations=planning_iterations,
            knowledge_sources=knowledge_sources,
        )

    async def _checkpoint(
        self,
        run_id: str,
        user_id: str,
        step: int,
        phase: str,
        trace: list[dict],
        intent: str,
        extra: dict | None = None,
    ) -> None:
        if self.checkpoints is None:
            return
        state = {
            "intent": intent,
            "executed_tools": [item["tool"] for item in trace],
            **(extra or {}),
        }
        await self.checkpoints.save(run_id, user_id, step, phase, state)
