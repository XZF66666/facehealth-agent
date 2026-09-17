import json
import math
from pathlib import Path
import re


class KnowledgeRetriever:
    def __init__(self, document_path: Path) -> None:
        self.documents = [
            json.loads(line)
            for line in document_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        self.tokens = [self._tokens(item["title"] + item["content"]) for item in self.documents]

    def search(self, query: str, limit: int = 3) -> list[dict]:
        query_tokens = self._tokens(query)
        scored = []
        for document, tokens in zip(self.documents, self.tokens):
            overlap = query_tokens & tokens
            has_ascii_term = any(re.fullmatch(r"[a-z0-9]+", token) for token in overlap)
            if not overlap or (len(overlap) < 2 and not has_ascii_term):
                continue
            score = sum(math.log((len(self.documents) + 1) / (1 + sum(t in x for x in self.tokens))) + 1 for t in overlap)
            scored.append((score, document))
        scored.sort(key=lambda item: (-item[0], item[1]["id"]))
        return [{**document, "score": round(score, 3)} for score, document in scored[:limit]]

    @staticmethod
    def _tokens(text: str) -> set[str]:
        normalized = text.lower()
        words = set(re.findall(r"[a-z0-9]+", normalized))
        chinese_runs = re.findall(r"[\u4e00-\u9fff]+", normalized)
        for run in chinese_runs:
            words.update(run[index : index + 2] for index in range(max(1, len(run) - 1)))
            words.update(run)
        return words
