from collections.abc import Awaitable, Callable
from pathlib import Path

import aiosqlite


SummaryCallback = Callable[[str, list[dict[str, str]]], Awaitable[str]]


class ConversationStore:
    def __init__(self, database_path: Path, max_history_messages: int = 12) -> None:
        self.database_path = database_path
        self.max_history_messages = max_history_messages

    async def initialize(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS conversation_message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    client_message_id TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cursor = await db.execute("PRAGMA table_info(conversation_message)")
            columns = {row[1] for row in await cursor.fetchall()}
            await cursor.close()
            if "client_message_id" not in columns:
                await db.execute(
                    "ALTER TABLE conversation_message ADD COLUMN client_message_id TEXT"
                )
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS conversation_summary (
                    conversation_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(conversation_id, user_id)
                )
                """
            )
            await db.execute(
                "CREATE INDEX IF NOT EXISTS idx_conversation_owner "
                "ON conversation_message(conversation_id, user_id, id)"
            )
            await db.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_client_message "
                "ON conversation_message(conversation_id, client_message_id, role) "
                "WHERE client_message_id IS NOT NULL"
            )
            await db.commit()

    async def append(
        self,
        conversation_id: str,
        user_id: str,
        role: str,
        content: str,
        client_message_id: str | None = None,
    ) -> bool:
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                "INSERT OR IGNORE INTO conversation_message("
                "conversation_id, user_id, role, content, client_message_id"
                ") VALUES (?, ?, ?, ?, ?)",
                (conversation_id, user_id, role, content, client_message_id),
            )
            await db.commit()
            inserted = cursor.rowcount > 0
            await cursor.close()
        return inserted

    async def context(
        self, conversation_id: str, user_id: str
    ) -> tuple[str, list[dict[str, str]]]:
        async with aiosqlite.connect(self.database_path) as db:
            summary_cursor = await db.execute(
                "SELECT summary FROM conversation_summary "
                "WHERE conversation_id=? AND user_id=?",
                (conversation_id, user_id),
            )
            summary_row = await summary_cursor.fetchone()
            await summary_cursor.close()
            cursor = await db.execute(
                "SELECT role, content FROM conversation_message "
                "WHERE conversation_id=? AND user_id=? "
                "ORDER BY id DESC LIMIT ?",
                (conversation_id, user_id, self.max_history_messages),
            )
            rows = await cursor.fetchall()
            await cursor.close()
        rows.reverse()
        return (
            summary_row[0] if summary_row else "",
            [{"role": role, "content": content} for role, content in rows],
        )

    async def recent(
        self, conversation_id: str, user_id: str | None = None
    ) -> list[dict[str, str]]:
        if user_id is None:
            async with aiosqlite.connect(self.database_path) as db:
                cursor = await db.execute(
                    "SELECT role, content FROM conversation_message "
                    "WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
                    (conversation_id, self.max_history_messages),
                )
                rows = await cursor.fetchall()
                await cursor.close()
            rows.reverse()
            return [{"role": role, "content": content} for role, content in rows]
        return (await self.context(conversation_id, user_id))[1]

    async def find_message(
        self,
        conversation_id: str,
        client_message_id: str | None,
        role: str,
        user_id: str | None = None,
    ) -> str | None:
        if not client_message_id:
            return None
        query = (
            "SELECT content FROM conversation_message "
            "WHERE conversation_id=? AND client_message_id=? AND role=?"
        )
        params: list[str] = [conversation_id, client_message_id, role]
        if user_id is not None:
            query += " AND user_id=?"
            params.append(user_id)
        query += " ORDER BY id DESC LIMIT 1"
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(query, params)
            row = await cursor.fetchone()
            await cursor.close()
        return row[0] if row else None

    async def compact(
        self,
        conversation_id: str,
        user_id: str,
        summarize: SummaryCallback,
    ) -> bool:
        async with aiosqlite.connect(self.database_path) as db:
            cursor = await db.execute(
                "SELECT id, role, content FROM conversation_message "
                "WHERE conversation_id=? AND user_id=? ORDER BY id",
                (conversation_id, user_id),
            )
            rows = await cursor.fetchall()
            await cursor.close()
            if len(rows) <= self.max_history_messages:
                return False
            summary_cursor = await db.execute(
                "SELECT summary FROM conversation_summary "
                "WHERE conversation_id=? AND user_id=?",
                (conversation_id, user_id),
            )
            row = await summary_cursor.fetchone()
            await summary_cursor.close()
            previous_summary = row[0] if row else ""

        archived = rows[: -self.max_history_messages]
        messages = [
            {"role": role, "content": content} for _, role, content in archived
        ]
        summary = (await summarize(previous_summary, messages)).strip()[:3000]
        if not summary:
            return False
        archived_ids = [row[0] for row in archived]
        placeholders = ",".join("?" for _ in archived_ids)
        async with aiosqlite.connect(self.database_path) as db:
            await db.execute(
                """
                INSERT INTO conversation_summary(conversation_id, user_id, summary)
                VALUES (?, ?, ?)
                ON CONFLICT(conversation_id, user_id) DO UPDATE SET
                    summary=excluded.summary,
                    updated_at=CURRENT_TIMESTAMP
                """,
                (conversation_id, user_id, summary),
            )
            await db.execute(
                f"DELETE FROM conversation_message WHERE id IN ({placeholders})",
                archived_ids,
            )
            await db.commit()
        return True

    async def clear(self, conversation_id: str, user_id: str | None = None) -> None:
        async with aiosqlite.connect(self.database_path) as db:
            if user_id is None:
                await db.execute(
                    "DELETE FROM conversation_message WHERE conversation_id=?",
                    (conversation_id,),
                )
                await db.execute(
                    "DELETE FROM conversation_summary WHERE conversation_id=?",
                    (conversation_id,),
                )
            else:
                await db.execute(
                    "DELETE FROM conversation_message "
                    "WHERE conversation_id=? AND user_id=?",
                    (conversation_id, user_id),
                )
                await db.execute(
                    "DELETE FROM conversation_summary "
                    "WHERE conversation_id=? AND user_id=?",
                    (conversation_id, user_id),
                )
            await db.commit()
