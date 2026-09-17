import json

from pydantic import BaseModel

from app.agents.prompts import (
    ANALYZE_JSON_INSTRUCTION,
    CHAT_RESPONSE_INSTRUCTION,
    HEALTH_SAFETY_SYSTEM_PROMPT,
    WEEKLY_JSON_INSTRUCTION,
)
from app.schemas.agent import AnalyzeRequest, ChatRequest, WeeklyReportRequest
from app.schemas.health import RuleAssessment
from app.agents.orchestrator import PreparedChat


def _json(value: BaseModel | list | dict) -> str:
    if isinstance(value, BaseModel):
        value = value.model_dump(mode="json")
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build_analyze_messages(
    request: AnalyzeRequest, assessment: RuleAssessment
) -> list[dict[str, str]]:
    context = {
        "today_metrics": request.today_metrics.model_dump(mode="json"),
        "weekly_summary": request.weekly_summary.model_dump(mode="json"),
        "user_context": request.user_context.model_dump(mode="json"),
        "user_message": request.user_message,
        "rule_assessment": assessment.model_dump(mode="json"),
    }
    return [
        {"role": "system", "content": HEALTH_SAFETY_SYSTEM_PROMPT},
        {"role": "system", "content": ANALYZE_JSON_INSTRUCTION},
        {"role": "user", "content": "请分析以下脱敏健康数据：\n" + _json(context)},
    ]


def build_weekly_messages(
    request: WeeklyReportRequest, assessments: list[RuleAssessment]
) -> list[dict[str, str]]:
    context = {
        "weekly_records": [item.model_dump(mode="json") for item in request.weekly_records],
        "weekly_summary": request.weekly_summary.model_dump(mode="json"),
        "user_note": request.user_note,
        "rule_assessments": [item.model_dump(mode="json") for item in assessments],
    }
    return [
        {"role": "system", "content": HEALTH_SAFETY_SYSTEM_PROMPT},
        {"role": "system", "content": WEEKLY_JSON_INSTRUCTION},
        {"role": "user", "content": "请生成一周健康管理报告：\n" + _json(context)},
    ]


def build_chat_messages(
    request: ChatRequest,
    prepared: PreparedChat,
    history: list[dict[str, str]],
    conversation_summary: str = "",
    include_current_user: bool = True,
) -> list[dict[str, str]]:
    health_context = {
        "today_metrics": prepared.today_metrics.model_dump(mode="json")
        if prepared.today_metrics
        else None,
        "weekly_records": [
            item.model_dump(mode="json") for item in prepared.weekly_records
        ],
        "weekly_summary": prepared.weekly_summary.model_dump(mode="json"),
        "user_context": prepared.user_context.model_dump(mode="json"),
        "user_note": prepared.user_note,
        "tool_results": prepared.prompt_context(),
        "measurement_notice": (
            "指标来自手机人脸视频估计，仅用于个人健康管理参考；"
            "本轮未提供同步医疗设备真值。"
        ),
    }
    messages = [
        {"role": "system", "content": HEALTH_SAFETY_SYSTEM_PROMPT},
        {"role": "system", "content": CHAT_RESPONSE_INSTRUCTION},
        {
            "role": "system",
            "content": "当前脱敏健康上下文：" + _json(health_context),
        },
    ]
    if conversation_summary:
        messages.append(
            {
                "role": "system",
                "content": "较早会话摘要（仅用于延续用户偏好与话题）："
                + conversation_summary,
            }
        )
    messages.extend(history)
    if include_current_user:
        messages.append({"role": "user", "content": request.message})
    return messages
