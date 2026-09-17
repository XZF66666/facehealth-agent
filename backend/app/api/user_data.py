from fastapi import APIRouter, Depends, Query, Request

from app.schemas.user_data import (
    HealthRecordInput,
    HealthRecordSyncRequest,
    HealthRecordSyncResponse,
    UserProfile,
    UserProfileResponse,
)
from app.security import authenticated_user, resolve_user


router = APIRouter(prefix="/api/health", tags=["health-data"])


@router.post("/records/sync", response_model=HealthRecordSyncResponse)
async def sync_records(
    payload: HealthRecordSyncRequest,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> HealthRecordSyncResponse:
    user_id = resolve_user(current_user, "local_user_001")
    accepted = await request.app.state.health_record_store.sync(
        user_id, payload.records
    )
    return HealthRecordSyncResponse(accepted=accepted, user_id=user_id)


@router.get("/records", response_model=list[HealthRecordInput])
async def get_records(
    request: Request,
    days: int = Query(default=7, ge=1, le=90),
    current_user: str | None = Depends(authenticated_user),
) -> list[HealthRecordInput]:
    user_id = resolve_user(current_user, "local_user_001")
    return await request.app.state.health_record_store.recent(user_id, days)


@router.get("/profile", response_model=UserProfileResponse)
async def get_profile(
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> UserProfileResponse:
    user_id = resolve_user(current_user, "local_user_001")
    profile = await request.app.state.health_record_store.get_profile(user_id)
    return UserProfileResponse(user_id=user_id, profile=profile)


@router.put("/profile", response_model=UserProfileResponse)
async def update_profile(
    profile: UserProfile,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> UserProfileResponse:
    user_id = resolve_user(current_user, "local_user_001")
    await request.app.state.health_record_store.save_profile(user_id, profile)
    return UserProfileResponse(user_id=user_id, profile=profile)
