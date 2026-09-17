from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
import json
from pathlib import Path
from typing import Any, Callable

from app.agents.orchestrator import (
    AgentOrchestrator,
    IntentRouter,
    MeasurementQualityTool,
    TrendTool,
)
from app.schemas.agent import ChatRequest
from app.schemas.health import HealthMetrics, UserContext, WeeklySummary
from app.schemas.user_data import HealthRecordInput
from app.services.health_record_service import HealthRecordStore
from app.services.rule_engine import HealthRuleEngine


ROOT = Path(__file__).resolve().parent
DATASETS = ROOT / "datasets"
DEFAULT_REPORT_DIR = ROOT / "reports"


@dataclass
class EvalResult:
    suite: str
    passed: int
    total: int
    failures: list[dict[str, Any]]

    @property
    def accuracy(self) -> float:
        return self.passed / self.total if self.total else 0.0


def load_jsonl(name: str) -> list[dict[str, Any]]:
    path = DATASETS / name
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def evaluate_cases(
    suite: str,
    cases: list[dict[str, Any]],
    evaluator: Callable[[dict[str, Any]], tuple[bool, dict[str, Any]]],
) -> EvalResult:
    failures: list[dict[str, Any]] = []
    for case in cases:
        passed, detail = evaluator(case)
        if not passed:
            failures.append({"id": case["id"], **detail})
    return EvalResult(suite, len(cases) - len(failures), len(cases), failures)


def evaluate_intent() -> EvalResult:
    router = IntentRouter()

    def check(case: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
        actual = router.route(case["message"])
        return actual == case["expected"], {
            "expected": case["expected"],
            "actual": actual,
            "input": case["message"],
        }

    return evaluate_cases("intent_accuracy", load_jsonl("intent.jsonl"), check)


def evaluate_quality() -> EvalResult:
    tool = MeasurementQualityTool()

    def check(case: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
        metrics = HealthMetrics.model_validate(case["metrics"]) if case["metrics"] else None
        actual = tool.evaluate(metrics)
        passed = (
            actual["status"] == case["expected_status"]
            and actual["can_interpret"] is case["expected_can_interpret"]
        )
        return passed, {
            "expected": {
                "status": case["expected_status"],
                "can_interpret": case["expected_can_interpret"],
            },
            "actual": actual,
        }

    return evaluate_cases("quality_gate_accuracy", load_jsonl("quality.jsonl"), check)


def evaluate_risk() -> EvalResult:
    rules = HealthRuleEngine()

    def check(case: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
        metrics = HealthMetrics.model_validate(case["metrics"]) if case["metrics"] else None
        assessment = rules.assess(
            metrics,
            UserContext.model_validate(case.get("context", {})),
            WeeklySummary.model_validate(case.get("weekly", {})),
        )
        missing_flags = sorted(set(case["required_flags"]) - set(assessment.flags))
        warning_ok = not case.get("requires_forced_warning") or bool(
            assessment.forced_warning.strip()
        )
        passed = (
            assessment.risk_level == case["expected_level"]
            and not missing_flags
            and warning_ok
        )
        return passed, {
            "expected_level": case["expected_level"],
            "actual_level": assessment.risk_level,
            "missing_flags": missing_flags,
            "actual_flags": assessment.flags,
            "forced_warning_present": bool(assessment.forced_warning.strip()),
        }

    return evaluate_cases("risk_rule_accuracy", load_jsonl("risk.jsonl"), check)


def evaluate_trend() -> EvalResult:
    tool = TrendTool()

    def check(case: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
        records = [
            HealthRecordInput(
                client_record_id=f"{case['id']}-{index}",
                date=raw.get("date", f"2026-09-{index + 1:02d}"),
                metrics=HealthMetrics.model_validate(raw),
            )
            for index, raw in enumerate(case["records"])
        ]
        summary, detail = tool.analyze(records)
        expected_directions = case.get("expected_directions", {})
        actual_directions = {
            key: value["direction"] for key, value in detail["directions"].items()
        }
        passed = (
            detail["sample_count"] == case["expected_sample_count"]
            and summary.trend == case["expected_trend"]
            and all(actual_directions.get(key) == value for key, value in expected_directions.items())
        )
        return passed, {
            "expected_trend": case["expected_trend"],
            "actual_trend": summary.trend,
            "expected_directions": expected_directions,
            "actual_directions": actual_directions,
        }

    return evaluate_cases("trend_accuracy", load_jsonl("trend.jsonl"), check)


async def evaluate_workflow() -> EvalResult:
    cases = load_jsonl("workflow.jsonl")
    failures: list[dict[str, Any]] = []
    database_path = ROOT.parent / "data" / "agent_eval.db"
    database_path.parent.mkdir(parents=True, exist_ok=True)
    database_path.unlink(missing_ok=True)
    try:
        store = HealthRecordStore(database_path)
        await store.initialize()
        orchestrator = AgentOrchestrator(store, HealthRuleEngine())
        for case in cases:
            request = ChatRequest(
                user_id=case["id"],
                message=case["message"],
                today_metrics=case.get("today_metrics"),
                weekly_records=case.get("weekly_records", []),
                user_context=case.get("user_context", {}),
            )
            prepared = await orchestrator.prepare(request, case["id"])
            actual = {
                "intent": prepared.intent,
                "tools": prepared.tools_used,
                "quality": prepared.quality["status"],
                "risk": prepared.assessment.risk_level,
            }
            expected = {
                "intent": case["expected_intent"],
                "tools": case["expected_tools"],
                "quality": case["expected_quality"],
                "risk": case["expected_risk"],
            }
            if actual != expected:
                failures.append({"id": case["id"], "expected": expected, "actual": actual})
    finally:
        database_path.unlink(missing_ok=True)
    return EvalResult("workflow_accuracy", len(cases) - len(failures), len(cases), failures)


def render_markdown(payload: dict[str, Any]) -> str:
    lines = [
        "# FaceHealth Agent Evaluation Report",
        "",
        f"Generated at: `{payload['generated_at']}`",
        "",
        "## Summary",
        "",
        "| Suite | Passed | Total | Accuracy |",
        "| --- | ---: | ---: | ---: |",
    ]
    for result in payload["results"]:
        lines.append(
            f"| `{result['suite']}` | {result['passed']} | {result['total']} | "
            f"{result['accuracy'] * 100:.1f}% |"
        )
    lines.extend(
        [
            f"| **Overall** | **{payload['passed']}** | **{payload['total']}** | "
            f"**{payload['accuracy'] * 100:.1f}%** |",
            "",
            "## Scope",
            "",
            "This report evaluates deterministic Agent components: intent routing, measurement "
            "quality gating, risk rules, trend calculation, and end-to-end workflow preparation. "
            "It does not claim to measure LLM answer quality, RAG quality, or clinical accuracy.",
            "",
            "## Failures",
            "",
        ]
    )
    failures = [failure for result in payload["results"] for failure in result["failures"]]
    if not failures:
        lines.append("No failed cases.")
    else:
        lines.append("```json")
        lines.append(json.dumps(failures, ensure_ascii=False, indent=2))
        lines.append("```")
    lines.append("")
    return "\n".join(lines)


async def run(report_dir: Path) -> dict[str, Any]:
    results = [
        evaluate_intent(),
        evaluate_quality(),
        evaluate_risk(),
        evaluate_trend(),
        await evaluate_workflow(),
    ]
    passed = sum(result.passed for result in results)
    total = sum(result.total for result in results)
    payload = {
        "generated_at": datetime.now(UTC).isoformat(),
        "passed": passed,
        "total": total,
        "accuracy": passed / total if total else 0.0,
        "results": [
            {**asdict(result), "accuracy": result.accuracy} for result in results
        ],
    }
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "latest.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (report_dir / "latest.md").write_text(render_markdown(payload), encoding="utf-8")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate deterministic Agent components")
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--min-accuracy", type=float, default=1.0)
    args = parser.parse_args()
    payload = asyncio.run(run(args.report_dir))
    print(
        f"Agent eval: {payload['passed']}/{payload['total']} passed "
        f"({payload['accuracy'] * 100:.1f}%)"
    )
    print(f"Report: {(args.report_dir / 'latest.md').resolve()}")
    return 0 if payload["accuracy"] >= args.min_accuracy else 1


if __name__ == "__main__":
    raise SystemExit(main())
