import asyncio

from app.agents.orchestrator import (
    AgentOrchestrator,
    IntentRouter,
    MeasurementQualityTool,
    TrendTool,
)
from app.agents.tool_registry import ToolRegistry
from app.schemas.agent import ChatRequest
from app.schemas.health import HealthMetrics
from app.schemas.user_data import HealthRecordInput, UserProfile
from app.services.rule_engine import HealthRuleEngine


def metric(stress: float, hrv: float, quality: str = "合格") -> HealthMetrics:
    return HealthMetrics(
        heart_rate=72,
        respiratory_rate=16,
        hrv=hrv,
        stress_score=stress,
        fatigue_score=50,
        video_quality=quality,
    )


def test_intent_router_is_deterministic():
    router = IntentRouter()
    assert router.route("看看本周压力趋势") == "weekly_trend"
    assert router.route("这次测得准确吗") == "measurement_quality"
    assert router.route("我现在胸痛怎么办") == "urgent_symptom"


def test_trend_and_quality_tools_compute_without_llm():
    records = [
        HealthRecordInput(
            client_record_id="one", date="2026-07-25", metrics=metric(45, 58)
        ),
        HealthRecordInput(
            client_record_id="two", date="2026-07-26", metrics=metric(66, 44)
        ),
    ]
    summary, trend = TrendTool().analyze(records)
    assert summary.avg_stress_score == 55.5
    assert trend["directions"]["stress_score"]["direction"] == "up"
    assert trend["directions"]["hrv"]["direction"] == "down"
    assert MeasurementQualityTool().evaluate(metric(50, 50, "差"))[
        "can_interpret"
    ] is False


def test_tool_registry_enforces_whitelist_order_and_safety_tools():
    registry = ToolRegistry.health_tools()
    plan = registry.resolve(
        ["RiskTool", "UnknownTool", "TrendTool", "TrendTool"],
        "llm_tool_calling",
    )

    assert plan.selected == [
        "HealthRecordTool",
        "MeasurementQualityTool",
        "TrendTool",
        "RiskTool",
    ]
    assert plan.rejected == ["UnknownTool"]
    assert all(item["type"] == "function" for item in registry.schemas())


def test_orchestrator_executes_model_selected_tool_with_trace():
    class FakeRecordStore:
        async def get_profile(self, user_id):
            return UserProfile()

        async def recent(self, user_id, limit):
            return []

    async def scenario():
        orchestrator = AgentOrchestrator(FakeRecordStore(), HealthRuleEngine())
        prepared = await orchestrator.prepare(
            ChatRequest(
                message="帮我分析这些数据",
                today_metrics=metric(40, 50),
                weekly_records=[metric(40, 55), metric(55, 45)],
            ),
            "tool-user",
            requested_tools=["TrendTool"],
            planning_mode="llm_tool_calling",
        )
        assert prepared.planning_mode == "llm_tool_calling"
        assert prepared.tools_used == [
            "HealthRecordTool",
            "MeasurementQualityTool",
            "TrendTool",
            "RiskTool",
        ]
        assert [item["tool"] for item in prepared.tool_trace] == prepared.tools_used

    asyncio.run(scenario())


def test_plan_execute_observe_replans_until_model_stops():
    class FakeRecordStore:
        async def get_profile(self, user_id):
            return UserProfile()

        async def recent(self, user_id, limit):
            return []

    calls = []

    async def planner(schemas, observations):
        calls.append(dict(observations))
        return ["TrendTool"] if len(calls) == 1 else []

    async def scenario():
        orchestrator = AgentOrchestrator(
            FakeRecordStore(), HealthRuleEngine(), max_planning_iterations=3
        )
        prepared = await orchestrator.prepare(
            ChatRequest(
                message="帮我综合分析",
                weekly_records=[metric(40, 55), metric(55, 45)],
            ),
            "loop-user",
            planning_mode="llm_tool_calling",
            tool_planner=planner,
        )
        assert prepared.planning_iterations == 2
        assert prepared.planning_mode == "llm_tool_calling"
        assert "TrendTool" in prepared.tools_used
        assert calls[1]["last_observation"]["tool"] == "TrendTool"

    asyncio.run(scenario())
