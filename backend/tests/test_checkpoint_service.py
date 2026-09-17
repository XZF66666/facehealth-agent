import asyncio
from pathlib import Path

from app.services.checkpoint_service import AgentCheckpointStore


def test_agent_checkpoints_are_persisted_in_step_order():
    database_path = Path("data/test_agent_checkpoint.db")
    database_path.unlink(missing_ok=True)

    async def scenario():
        store = AgentCheckpointStore(database_path)
        await store.initialize()
        await store.save("run-1", "user-1", 0, "plan", {"requested": ["TrendTool"]})
        await store.save("run-1", "user-1", 1, "observe", {"sample_count": 7})
        checkpoints = await store.load("run-1", "user-1")
        assert [item["phase"] for item in checkpoints] == ["plan", "observe"]
        assert checkpoints[1]["state"]["sample_count"] == 7

    try:
        asyncio.run(scenario())
    finally:
        database_path.unlink(missing_ok=True)
