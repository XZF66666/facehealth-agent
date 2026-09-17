import json
from pathlib import Path
from typing import Any

import aiosqlite


class AgentCheckpointStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path

    async def initialize(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_checkpoint (
                    run_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    step INTEGER NOT NULL,
                    phase TEXT NOT NULL,
                    state_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(run_id, user_id, step)
                )
                """
            )
            await db.commit()

    async def save(
        self,
        run_id: str,
        user_id: str,
        step: int,
        phase: str,
        state: dict[str, Any],
    ) -> None:
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                INSERT INTO agent_checkpoint(run_id, user_id, step, phase, state_json)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(run_id, user_id, step) DO UPDATE SET
                    phase=excluded.phase,
                    state_json=excluded.state_json,
                    created_at=CURRENT_TIMESTAMP
                """,
                (run_id, user_id, step, phase, json.dumps(state, ensure_ascii=False)),
            )
            await db.commit()

    async def load(self, run_id: str, user_id: str) -> list[dict[str, Any]]:
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                "SELECT step, phase, state_json FROM agent_checkpoint "
                "WHERE run_id=? AND user_id=? ORDER BY step",
                (run_id, user_id),
            )
            rows = await cursor.fetchall()
            await cursor.close()
        return [
            {"step": step, "phase": phase, "state": json.loads(state_json)}
            for step, phase, state_json in rows
        ]

