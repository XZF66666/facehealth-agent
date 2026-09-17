import hashlib
import secrets
from datetime import datetime, timezone
from pathlib import Path

import aiosqlite


class AuthStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path

    async def initialize(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS auth_session (
                    token_hash TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL
                )
                """
            )
            await db.execute(
                "CREATE INDEX IF NOT EXISTS idx_auth_user_id ON auth_session(user_id)"
            )
            await db.commit()

    async def issue_anonymous(self, device_name: str) -> tuple[str, str]:
        user_id = "anon_" + secrets.token_hex(12)
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc).isoformat()
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                "INSERT INTO auth_session(token_hash, user_id, device_name, created_at, "
                "last_seen_at) VALUES (?, ?, ?, ?, ?)",
                (self._hash(token), user_id, device_name, now, now),
            )
            await db.commit()
        return user_id, token

    async def authenticate(self, token: str) -> str | None:
        token_hash = self._hash(token)
        now = datetime.now(timezone.utc).isoformat()
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                "SELECT user_id FROM auth_session WHERE token_hash=?", (token_hash,)
            )
            row = await cursor.fetchone()
            await cursor.close()
            if row:
                await db.execute(
                    "UPDATE auth_session SET last_seen_at=? WHERE token_hash=?",
                    (now, token_hash),
                )
                await db.commit()
        return row[0] if row else None

    @staticmethod
    def _hash(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()
