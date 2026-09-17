from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    stage: int
    required: bool = False
    parameters: dict[str, Any] | None = None

    def as_openai_tool(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters
                or {"type": "object", "properties": {}, "additionalProperties": False},
            },
        }


@dataclass(frozen=True)
class ToolPlan:
    requested: list[str]
    selected: list[str]
    rejected: list[str]
    planning_mode: str


class ToolRegistry:
    """Whitelist and policy layer between model-selected tools and execution."""

    def __init__(self, specs: Iterable[ToolSpec], max_tools: int = 5) -> None:
        self._specs = {spec.name: spec for spec in specs}
        self.max_tools = max_tools

    @classmethod
    def health_tools(cls) -> "ToolRegistry":
        return cls(
            [
                ToolSpec(
                    "HealthRecordTool",
                    "读取当前认证用户的近期结构化健康记录。数据身份由服务端决定。",
                    stage=10,
                    required=True,
                ),
                ToolSpec(
                    "MeasurementQualityTool",
                    "判断当前测量质量，决定指标能否用于解释或是否需要重测。",
                    stage=20,
                    required=True,
                ),
                ToolSpec(
                    "TrendTool",
                    "计算近期记录的平均值、变化方向和异常天数；趋势问题应调用。",
                    stage=30,
                ),
                ToolSpec(
                    "KnowledgeRetrievalTool",
                    "从经过审核的权威健康知识库检索相关解释，并返回可引用来源。",
                    stage=35,
                ),
                ToolSpec(
                    "RiskTool",
                    "根据确定性规则执行风险分级和紧急症状检查；不能被模型绕过。",
                    stage=40,
                    required=True,
                ),
            ]
        )

    def schemas(self) -> list[dict[str, Any]]:
        return [
            spec.as_openai_tool()
            for spec in sorted(self._specs.values(), key=lambda item: item.stage)
        ]

    def resolve(
        self,
        requested: Iterable[str],
        planning_mode: str,
        policy_required: Iterable[str] = (),
    ) -> ToolPlan:
        requested_names = list(dict.fromkeys(requested))
        rejected = [name for name in requested_names if name not in self._specs]
        selected = {
            name for name in requested_names if name in self._specs
        }
        selected.update(spec.name for spec in self._specs.values() if spec.required)
        selected.update(name for name in policy_required if name in self._specs)
        ordered = sorted(selected, key=lambda name: self._specs[name].stage)
        if len(ordered) > self.max_tools:
            ordered = ordered[: self.max_tools]
        return ToolPlan(requested_names, ordered, rejected, planning_mode)
