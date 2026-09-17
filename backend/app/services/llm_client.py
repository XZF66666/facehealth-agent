import json
from collections.abc import AsyncIterator

import httpx

from app.config import Settings


class LlmClientError(RuntimeError):
    pass


class OpenAICompatibleLlmClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    @property
    def configured(self) -> bool:
        return self.settings.llm_configured

    async def complete(self, messages: list[dict[str, str]], json_mode: bool = False) -> str:
        self._require_configuration()
        payload: dict = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": self.settings.llm_temperature,
            "stream": False,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        try:
            async with httpx.AsyncClient(timeout=self.settings.llm_timeout_seconds) as client:
                response = await client.post(
                    self._endpoint,
                    headers=self._headers,
                    json=payload,
                )
                response.raise_for_status()
                body = response.json()
                return body["choices"][0]["message"]["content"]
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as exc:
            raise LlmClientError(f"LLM request failed: {type(exc).__name__}") from exc

    async def complete_json(self, messages: list[dict[str, str]]) -> dict:
        content = await self.complete(messages, json_mode=True)
        try:
            return json.loads(content)
        except json.JSONDecodeError as exc:
            raise LlmClientError("LLM returned invalid JSON") from exc

    async def select_tools(
        self,
        messages: list[dict[str, str]],
        tools: list[dict],
    ) -> list[str]:
        """Ask an OpenAI-compatible model to select tools without executing them."""
        self._require_configuration()
        payload = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": 0,
            "stream": False,
            "tools": tools,
            "tool_choice": "auto",
        }
        try:
            async with httpx.AsyncClient(timeout=self.settings.llm_timeout_seconds) as client:
                response = await client.post(self._endpoint, headers=self._headers, json=payload)
                response.raise_for_status()
                message = response.json()["choices"][0]["message"]
                calls = message.get("tool_calls", [])
                selected: list[str] = []
                for call in calls:
                    function = call.get("function", {})
                    name = function.get("name")
                    arguments = json.loads(function.get("arguments") or "{}")
                    if name and isinstance(arguments, dict):
                        selected.append(str(name))
                return list(dict.fromkeys(selected))
        except (httpx.HTTPError, json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise LlmClientError(f"LLM tool planning failed: {type(exc).__name__}") from exc

    async def stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        self._require_configuration()
        payload = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": self.settings.llm_temperature,
            "stream": True,
        }
        try:
            timeout = httpx.Timeout(self.settings.llm_timeout_seconds, read=None)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST", self._endpoint, headers=self._headers, json=payload
                ) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if not data or data == "[DONE]":
                            continue
                        try:
                            delta = json.loads(data)["choices"][0]["delta"].get("content", "")
                        except (json.JSONDecodeError, KeyError, IndexError, TypeError):
                            continue
                        if delta:
                            yield delta
        except httpx.HTTPError as exc:
            raise LlmClientError(f"LLM stream failed: {type(exc).__name__}") from exc

    def _require_configuration(self) -> None:
        if not self.configured:
            raise LlmClientError("LLM_API_KEY is not configured")

    @property
    def _endpoint(self) -> str:
        return self.settings.llm_base_url.rstrip("/") + "/chat/completions"

    @property
    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.settings.llm_api_key}",
            "Content-Type": "application/json",
        }
