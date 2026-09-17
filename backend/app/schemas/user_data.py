from typing import Literal

from pydantic import BaseModel, Field

from app.schemas.health import HealthMetrics, UserContext


class AnonymousSessionRequest(BaseModel):
    device_name: str = Field(default="android", max_length=80)


class AnonymousSessionResponse(BaseModel):
    user_id: str
    access_token: str
    token_type: str = "bearer"


class HealthRecordInput(BaseModel):
    client_record_id: str = Field(min_length=1, max_length=128)
    date: str = Field(min_length=8, max_length=32)
    created_at: str | None = Field(default=None, max_length=64)
    metrics: HealthMetrics
    context: UserContext = Field(default_factory=UserContext)
    user_note: str = Field(default="", max_length=2000)


class HealthRecordSyncRequest(BaseModel):
    records: list[HealthRecordInput] = Field(default_factory=list, max_length=31)


class HealthRecordSyncResponse(BaseModel):
    accepted: int
    user_id: str


class UserProfile(BaseModel):
    age_range: str = Field(default="", max_length=32)
    activity_level: str = Field(default="", max_length=64)
    exercise_habits: list[str] = Field(default_factory=list, max_length=12)
    focus_areas: list[str] = Field(default_factory=list, max_length=12)
    response_style: Literal["concise", "balanced", "detailed"] = "balanced"
    baseline_days: int = Field(default=28, ge=7, le=90)


class UserProfileResponse(BaseModel):
    user_id: str
    profile: UserProfile
