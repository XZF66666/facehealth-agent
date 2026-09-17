from typing import Any

from pydantic import BaseModel, Field


class HealthMetrics(BaseModel):
    heart_rate: float = Field(ge=20, le=240)
    respiratory_rate: float = Field(ge=3, le=80)
    hrv: float = Field(ge=0, le=500)
    stress_score: float = Field(ge=0, le=100)
    fatigue_score: float = Field(ge=0, le=100)
    video_quality: str = "未知"
    date: str | None = None


class WeeklySummary(BaseModel):
    avg_heart_rate: float = 0
    avg_respiratory_rate: float = 0
    avg_hrv: float = 0
    avg_stress_score: float = 0
    avg_fatigue_score: float = 0
    abnormal_days: int = 0
    trend: str = "暂无足够数据"


class UserContext(BaseModel):
    sleep_hours: float = 0
    stress_level: str = "一般"
    is_late_sleep: bool = False
    is_coffee: bool = False
    is_after_exercise: bool = False
    symptoms: list[str] = Field(default_factory=list)


class RuleAssessment(BaseModel):
    risk_level: str = "normal"
    flags: list[str] = Field(default_factory=list)
    findings: list[str] = Field(default_factory=list)
    forced_warning: str = ""
    metadata: dict[str, Any] = Field(default_factory=dict)

