from typing import Any

from pydantic import BaseModel, Field

from app.schemas.health import HealthMetrics, UserContext, WeeklySummary


class AnalyzeRequest(BaseModel):
    user_id: str = Field(default="local_user_001", max_length=128)
    user_message: str = Field(default="", max_length=2000)
    today_metrics: HealthMetrics
    weekly_summary: WeeklySummary = Field(default_factory=WeeklySummary)
    user_context: UserContext = Field(default_factory=UserContext)


class AnalyzeResponse(BaseModel):
    request_id: str
    risk_level: str
    rule_flags: list[str]
    today_status: str
    weekly_trend: str
    possible_reason: str
    suggestion: str
    risk_warning: str
    model_used: bool


class WeeklyReportRequest(BaseModel):
    user_id: str = Field(default="local_user_001", max_length=128)
    weekly_records: list[HealthMetrics] = Field(default_factory=list, max_length=31)
    weekly_summary: WeeklySummary = Field(default_factory=WeeklySummary)
    user_note: str = Field(default="", max_length=2000)


class WeeklyReportResponse(BaseModel):
    request_id: str
    risk_level: str
    weekly_report: str
    key_findings: list[str]
    suggestions: list[str]
    risk_warning: str
    model_used: bool


class ChatRequest(BaseModel):
    user_id: str = Field(default="local_user_001", max_length=128)
    conversation_id: str | None = Field(default=None, max_length=128)
    client_message_id: str | None = Field(default=None, min_length=8, max_length=128)
    message: str = Field(min_length=1, max_length=2000)
    today_metrics: HealthMetrics | None = None
    weekly_records: list[HealthMetrics] = Field(default_factory=list, max_length=7)
    weekly_summary: WeeklySummary = Field(default_factory=WeeklySummary)
    user_context: UserContext = Field(default_factory=UserContext)
    user_note: str = Field(default="", max_length=2000)
    stream: bool = False


class ChatResponse(BaseModel):
    request_id: str
    conversation_id: str
    reply: str
    risk_level: str
    rule_flags: list[str]
    model_used: bool
    intent: str = "health_education"
    tools_used: list[str] = Field(default_factory=list)
    data_source: str = "request_fallback"
    planning_mode: str = "deterministic_fallback"
    tool_trace: list[dict[str, Any]] = Field(default_factory=list)
    run_id: str = ""
    planning_iterations: int = 0
    knowledge_sources: list[dict[str, Any]] = Field(default_factory=list)
