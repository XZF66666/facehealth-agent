import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.agents.health_agent import HealthAgent
from app.agents.orchestrator import AgentOrchestrator
from app.api.agent import router as agent_router
from app.api.auth import router as auth_router
from app.api.health import router as health_router
from app.api.user_data import router as user_data_router
from app.config import get_settings
from app.middleware.request_id import RequestIdMiddleware
from app.services.llm_client import OpenAICompatibleLlmClient
from app.services.auth_service import AuthStore
from app.services.health_record_service import HealthRecordStore
from app.services.memory_service import ConversationStore
from app.services.rule_engine import HealthRuleEngine
from app.services.checkpoint_service import AgentCheckpointStore

settings = get_settings()
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("facehealth")


@asynccontextmanager
async def lifespan(app: FastAPI):
    store = ConversationStore(settings.database_file, settings.max_history_messages)
    auth_store = AuthStore(settings.database_file)
    health_record_store = HealthRecordStore(settings.database_file)
    checkpoint_store = AgentCheckpointStore(settings.database_file)
    await store.initialize()
    await auth_store.initialize()
    await health_record_store.initialize()
    await checkpoint_store.initialize()
    app.state.settings = settings
    app.state.conversation_store = store
    app.state.auth_store = auth_store
    app.state.health_record_store = health_record_store
    app.state.checkpoint_store = checkpoint_store
    rules = HealthRuleEngine()
    app.state.health_agent = HealthAgent(
        rules,
        OpenAICompatibleLlmClient(settings),
        store,
        AgentOrchestrator(health_record_store, rules, checkpoint_store),
    )
    logger.info("FaceHealth backend started; llm_configured=%s", settings.llm_configured)
    yield
    logger.info("FaceHealth backend stopped")


app = FastAPI(
    title=settings.app_name,
    version="0.2.0",
    description="FaceHealth Agent 健康规则与大模型编排服务",
    lifespan=lifespan,
)
app.add_middleware(RequestIdMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization", "X-Request-ID"],
)
app.include_router(health_router)
app.include_router(auth_router)
app.include_router(user_data_router)
app.include_router(agent_router)


@app.exception_handler(Exception)
async def unhandled_exception(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", "unknown")
    logger.exception("Unhandled request error request_id=%s", request_id)
    return JSONResponse(
        status_code=500,
        content={
            "error": {
                "code": "INTERNAL_ERROR",
                "message": "服务暂时不可用，请稍后重试",
                "request_id": request_id,
            }
        },
    )
