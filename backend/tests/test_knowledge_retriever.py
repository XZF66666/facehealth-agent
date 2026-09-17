from pathlib import Path

from app.services.knowledge_retriever import KnowledgeRetriever


def retriever() -> KnowledgeRetriever:
    return KnowledgeRetriever(
        Path("app/knowledge/health_guidance.jsonl")
    )


def test_sleep_query_returns_cdc_source():
    results = retriever().search("成年人睡眠不足应该注意什么")
    assert results
    assert results[0]["source"] == "Centers for Disease Control and Prevention"
    assert results[0]["url"].startswith("https://")


def test_unrelated_query_does_not_invent_sources():
    assert retriever().search("量子芯片编译器") == []
