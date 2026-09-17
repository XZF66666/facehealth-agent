import json
import logging
from uuid import uuid4

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.schemas.agent import (
    AnalyzeRequest,
    AnalyzeResponse,
    ChatRequest,
    ChatResponse,
    WeeklyReportRequest,
    WeeklyReportResponse,
)
from app.security import authenticated_user, resolve_user

router = APIRouter(prefix="/api/agent", tags=["agent"])
logger = logging.getLogger("facehealth.agent_api")


def _request_id(request: Request) -> str:
    return getattr(request.state, "request_id", uuid4().hex)


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze(
    payload: AnalyzeRequest,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> dict:
    payload = payload.model_copy(
        update={"user_id": resolve_user(current_user, payload.user_id)}
    )
    return await request.app.state.health_agent.analyze(payload, _request_id(request))


@router.post("/weekly-report", response_model=WeeklyReportResponse)
async def weekly_report(
    payload: WeeklyReportRequest,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> dict:
    payload = payload.model_copy(
        update={"user_id": resolve_user(current_user, payload.user_id)}
    )
    return await request.app.state.health_agent.weekly_report(payload, _request_id(request))


@router.post("/chat")
async def chat(
    payload: ChatRequest,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
):
    payload = payload.model_copy(
        update={"user_id": resolve_user(current_user, payload.user_id)}
    )
    request_id = _request_id(request)
    prepared = await request.app.state.health_agent.prepare_chat(
        payload, payload.user_id
    )
    if not payload.stream:
        result = await request.app.state.health_agent.chat(
            payload, request_id, prepared
        )
        return ChatResponse.model_validate(result)

    conversation_id = payload.conversation_id or uuid4().hex

    async def event_stream():
        meta = {
            "request_id": request_id,
            "conversation_id": conversation_id,
            "intent": prepared.intent,
            "tools_used": prepared.tools_used,
            "data_source": prepared.data_source,
            "planning_mode": prepared.planning_mode,
            "tool_trace": prepared.tool_trace,
            "run_id": prepared.run_id,
            "planning_iterations": prepared.planning_iterations,
            "knowledge_sources": prepared.knowledge_sources,
        }
        yield _sse("meta", meta)
        try:
            async for chunk in request.app.state.health_agent.stream_chat(
                payload, conversation_id, prepared
            ):
                yield _sse("delta", {"text": chunk})
            yield _sse("done", {"finish_reason": "stop"})
        except Exception:
            logger.exception(
                "Chat stream failed request_id=%s conversation_id=%s",
                request_id,
                conversation_id,
            )
            yield _sse(
                "error",
                {"code": "STREAM_FAILED", "message": "流式回复中断，请稍后重试"},
            )

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.delete("/chat/{conversation_id}", status_code=204)
async def clear_chat(
    conversation_id: str,
    request: Request,
    current_user: str | None = Depends(authenticated_user),
) -> None:
    await request.app.state.conversation_store.clear(conversation_id, current_user)


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
