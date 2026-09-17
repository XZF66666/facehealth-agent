import asyncio

from app.agents.tool_registry import ToolRegistry
from app.config import Settings
from app.services.llm_client import OpenAICompatibleLlmClient


async def main() -> int:
    settings = Settings()
    if not settings.llm_configured:
        print("SKIP: configure LLM_API_KEY in backend/.env before running this check")
        return 2
    client = OpenAICompatibleLlmClient(settings)
    selected = await client.select_tools(
        [
            {"role": "system", "content": "只选择回答问题所需的工具。"},
            {"role": "user", "content": "请分析我最近七天的健康趋势。"},
        ],
        ToolRegistry.health_tools().schemas(),
    )
    print("provider=OpenAI-compatible")
    print(f"model={settings.llm_model}")
    print(f"selected_tools={selected}")
    if "TrendTool" not in selected:
        print("FAIL: model did not select TrendTool")
        return 1
    print("PASS: Function Calling response is compatible")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

