from app.schemas.health import HealthMetrics, UserContext, WeeklySummary
from app.services.rule_engine import HealthRuleEngine


def metrics(**overrides) -> HealthMetrics:
    values = {
        "heart_rate": 72,
        "respiratory_rate": 16,
        "hrv": 48,
        "stress_score": 40,
        "fatigue_score": 45,
        "video_quality": "合格",
    }
    values.update(overrides)
    return HealthMetrics(**values)


def test_normal_metrics_are_not_escalated():
    result = HealthRuleEngine().assess(metrics(), UserContext(), WeeklySummary())
    assert result.risk_level == "normal"
    assert not result.forced_warning


def test_red_flag_symptom_forces_urgent_warning():
    result = HealthRuleEngine().assess(
        metrics(), UserContext(symptoms=["胸痛"]), WeeklySummary()
    )
    assert result.risk_level == "urgent"
    assert "RED_FLAG_SYMPTOM" in result.flags
    assert "及时就医" in result.forced_warning


def test_hrv_is_compared_with_personal_baseline():
    result = HealthRuleEngine().assess(
        metrics(hrv=30), UserContext(), WeeklySummary(avg_hrv=50)
    )
    assert "HRV_BELOW_BASELINE" in result.flags

