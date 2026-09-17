import asyncio
from pathlib import Path

from evals.run import run


def test_deterministic_agent_eval_passes():
    report_dir = Path("evals/.test-output")
    payload = asyncio.run(run(report_dir))

    assert payload["total"] >= 40
    assert payload["passed"] == payload["total"]
    assert (report_dir / "latest.json").exists()
    assert (report_dir / "latest.md").exists()
