import asyncio

from app.config import Settings
from app.services.llm_client import OpenAICompatibleLlmClient


def test_openai_compatible_tool_calls_are_parsed_and_deduplicated(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "choices": [{"message": {"tool_calls": [
                    {"function": {"name": "TrendTool", "arguments": "{}"}},
                    {"function": {"name": "TrendTool", "arguments": "{}"}},
                ]}}]
            }

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, traceback):
            return False

        async def post(self, *args, **kwargs):
            assert kwargs["json"]["tool_choice"] == "auto"
            assert kwargs["json"]["tools"][0]["type"] == "function"
            return FakeResponse()

    monkeypatch.setattr(
        "app.services.llm_client.httpx.AsyncClient", lambda **kwargs: FakeClient()
    )
    client = OpenAICompatibleLlmClient(Settings(llm_api_key="test-key"))
    selected = asyncio.run(
        client.select_tools(
            [{"role": "user", "content": "看看趋势"}],
            [{"type": "function", "function": {
                "name": "TrendTool",
                "description": "计算趋势",
                "parameters": {"type": "object", "properties": {}},
            }}],
        )
    )

    assert selected == ["TrendTool"]
