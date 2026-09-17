from fastapi import APIRouter, Request

from app.schemas.user_data import AnonymousSessionRequest, AnonymousSessionResponse


router = APIRouter(prefix="/api/auth", tags=["auth"])


@router.post("/anonymous", response_model=AnonymousSessionResponse)
async def create_anonymous_session(
    payload: AnonymousSessionRequest, request: Request
) -> AnonymousSessionResponse:
    user_id, token = await request.app.state.auth_store.issue_anonymous(
        payload.device_name
    )
    return AnonymousSessionResponse(user_id=user_id, access_token=token)
