import asyncio
from pathlib import Path

from app.services.memory_service import ConversationStore


def test_client_message_id_prevents_duplicate_roles():
    database_path = Path("data/test_memory_service.db")
    database_path.unlink(missing_ok=True)

    async def scenario():
        store = ConversationStore(database_path)
        await store.initialize()
        first_user = await store.append("conversation", "user", "user", "问题", "message-001")
        duplicate_user = await store.append(
            "conversation", "user", "user", "重复问题", "message-001"
        )
        first_answer = await store.append(
            "conversation", "user", "assistant", "回答", "message-001"
        )
        duplicate_answer = await store.append(
            "conversation", "user", "assistant", "重复回答", "message-001"
        )

        assert first_user is True
        assert duplicate_user is False
        assert first_answer is True
        assert duplicate_answer is False
        assert await store.find_message("conversation", "message-001", "user") == "问题"
        assert (
            await store.find_message("conversation", "message-001", "assistant")
            == "回答"
        )

    try:
        asyncio.run(scenario())
    finally:
        database_path.unlink(missing_ok=True)


def test_old_messages_are_summarized_and_raw_history_is_bounded():
    database_path = Path("data/test_memory_compaction.db")
    database_path.unlink(missing_ok=True)

    async def scenario():
        store = ConversationStore(database_path, max_history_messages=4)
        await store.initialize()
        for index in range(6):
            await store.append(
                "conversation",
                "user-a",
                "user" if index % 2 == 0 else "assistant",
                f"message-{index}",
            )

        async def summarize(previous, messages):
            assert previous == ""
            return "用户关注恢复状态"

        compacted = await store.compact(
            "conversation", "user-a", summarize
        )
        summary, recent = await store.context("conversation", "user-a")
        assert compacted is True
        assert summary == "用户关注恢复状态"
        assert len(recent) == 4

    try:
        asyncio.run(scenario())
    finally:
        database_path.unlink(missing_ok=True)
