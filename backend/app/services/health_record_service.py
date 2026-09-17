import json
from pathlib import Path

import aiosqlite

from app.schemas.user_data import HealthRecordInput, UserProfile


class HealthRecordStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path

    async def initialize(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS user_health_record (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    client_record_id TEXT NOT NULL,
                    record_date TEXT NOT NULL,
                    created_at TEXT,
                    metrics_json TEXT NOT NULL,
                    context_json TEXT NOT NULL,
                    user_note TEXT NOT NULL,
                    synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(user_id, client_record_id)
                )
                """
            )
            await db.execute(
                "CREATE INDEX IF NOT EXISTS idx_health_record_user_date "
                "ON user_health_record(user_id, record_date, id)"
            )
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS user_profile (
                    user_id TEXT PRIMARY KEY,
                    profile_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            await db.commit()

    async def sync(self, user_id: str, records: list[HealthRecordInput]) -> int:
        if not records:
            return 0
        async with aiosqlite.connect(self.database_path) as db:
            for record in records:
                await db.execute(
                    """
                    INSERT INTO user_health_record(
                        user_id, client_record_id, record_date, created_at,
                        metrics_json, context_json, user_note
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(user_id, client_record_id) DO UPDATE SET
                        record_date=excluded.record_date,
                        created_at=excluded.created_at,
                        metrics_json=excluded.metrics_json,
                        context_json=excluded.context_json,
                        user_note=excluded.user_note,
                        synced_at=CURRENT_TIMESTAMP
                    """,
                    (
                        user_id,
                        record.client_record_id,
                        record.date,
                        record.created_at,
                        record.metrics.model_dump_json(),
                        record.context.model_dump_json(),
                        record.user_note,
                    ),
                )
            await db.commit()
        return len(records)

    async def recent(self, user_id: str, limit: int = 28) -> list[HealthRecordInput]:
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                """
                SELECT current.client_record_id, current.record_date,
                       current.created_at, current.metrics_json,
                       current.context_json, current.user_note
                FROM user_health_record AS current
                WHERE current.user_id=?
                  AND current.id = (
                      SELECT latest.id
                      FROM user_health_record AS latest
                      WHERE latest.user_id=current.user_id
                        AND latest.record_date=current.record_date
                      ORDER BY latest.id DESC
                      LIMIT 1
                  )
                ORDER BY current.record_date DESC, current.id DESC
                LIMIT ?
                """,
                (user_id, limit),
            )
            rows = await cursor.fetchall()
            await cursor.close()
        records = [
            HealthRecordInput(
                client_record_id=row[0],
                date=row[1],
                created_at=row[2],
                metrics=json.loads(row[3]),
                context=json.loads(row[4]),
                user_note=row[5],
            )
            for row in rows
        ]
        records.reverse()
        return records

    async def get_profile(self, user_id: str) -> UserProfile:
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                "SELECT profile_json FROM user_profile WHERE user_id=?", (user_id,)
            )
            row = await cursor.fetchone()
            await cursor.close()
        return UserProfile.model_validate_json(row[0]) if row else UserProfile()

    async def save_profile(self, user_id: str, profile: UserProfile) -> None:
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                INSERT INTO user_profile(user_id, profile_json)
                VALUES (?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    profile_json=excluded.profile_json,
                    updated_at=CURRENT_TIMESTAMP
                """,
                (user_id, profile.model_dump_json()),
            )
            await db.commit()
