"""Embeddings, behind a provider interface.

Two implementations. The API-backed one is used when a key is configured. The fallback is a real
lexical embedding — normalised token frequencies with inverse-document weighting — not a hash of the
text pretending to be a vector. That distinction matters: a fake embedding would make retrieval look
like it works while returning arbitrary documents, which for repair instructions is worse than
returning nothing.

The lexical fallback genuinely retrieves: it finds documents sharing distinctive vocabulary with the
query. It just doesn't understand synonyms the way a neural embedding does.
"""

from __future__ import annotations

import math
import re
from abc import ABC, abstractmethod
from collections import Counter
from typing import Dict, List, Sequence

import structlog

from app.core.config import get_settings

log = structlog.get_logger()

_TOKEN = re.compile(r"[a-z][a-z'-]+")

# Words that appear everywhere carry no signal about which procedure to retrieve.
STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
    "is", "are", "was", "were", "be", "been", "being", "to", "of", "in", "on", "at", "for", "with",
    "from", "by", "as", "it", "its", "you", "your", "i", "my", "we", "our", "they", "them", "there",
    "can", "will", "would", "should", "could", "do", "does", "did", "have", "has", "had", "not",
    "no", "yes", "so", "up", "down", "out", "off", "over", "under", "about", "into", "when",
}


def tokenize(text: str) -> List[str]:
    return [t for t in _TOKEN.findall((text or "").lower()) if t not in STOPWORDS and len(t) > 2]


class EmbeddingProvider(ABC):
    """One method, so a vendor swap touches nothing else."""

    @abstractmethod
    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        ...

    @property
    @abstractmethod
    def name(self) -> str:
        ...


class LexicalEmbedding(EmbeddingProvider):
    """TF-IDF style vectors over a vocabulary learned from the corpus.

    Deterministic, free, and offline. Retrieval quality is lower than a neural embedding because it
    matches words rather than meaning — "leak" won't match "drip" — which is why the corpus is
    written with the vocabulary customers actually use.
    """

    def __init__(self) -> None:
        self.vocabulary: Dict[str, int] = {}
        self.idf: Dict[str, float] = {}
        self._fitted = False

    def fit(self, corpus: Sequence[str]) -> None:
        """Learn the vocabulary and document frequencies. Called once at ingestion."""
        docs = [tokenize(t) for t in corpus]
        document_count = max(len(docs), 1)
        appearances: Counter[str] = Counter()
        for tokens in docs:
            appearances.update(set(tokens))

        self.vocabulary = {term: i for i, term in enumerate(sorted(appearances))}
        # Smoothed IDF: a term in every document contributes almost nothing.
        self.idf = {
            term: math.log((document_count + 1) / (count + 1)) + 1.0
            for term, count in appearances.items()
        }
        self._fitted = True
        log.info("lexical_embedding_fitted", vocabulary=len(self.vocabulary), documents=document_count)

    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        if not self._fitted:
            # Embedding before fitting would silently produce zero vectors and match nothing.
            raise RuntimeError("The lexical embedding must be fitted on the corpus first")

        vectors: List[List[float]] = []
        for text in texts:
            counts = Counter(tokenize(text))
            vector = [0.0] * len(self.vocabulary)
            for term, count in counts.items():
                index = self.vocabulary.get(term)
                if index is None:
                    continue  # a query word absent from the corpus tells us nothing
                vector[index] = (1 + math.log(count)) * self.idf.get(term, 1.0)
            vectors.append(_normalise(vector))
        return vectors

    @property
    def name(self) -> str:
        return "lexical-tfidf-v1"


class ApiEmbedding(EmbeddingProvider):
    """Neural embeddings from the configured provider."""

    def __init__(self, model: str = "text-embedding-3-small") -> None:
        from openai import OpenAI

        settings = get_settings()
        self.model = model
        self.client = OpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url,
                             timeout=settings.ai_timeout_seconds)

    def embed(self, texts: Sequence[str]) -> List[List[float]]:
        response = self.client.embeddings.create(model=self.model, input=list(texts))
        return [item.embedding for item in response.data]

    @property
    def name(self) -> str:
        return self.model


def _normalise(vector: List[float]) -> List[float]:
    """Unit length, so cosine similarity is a dot product."""
    magnitude = math.sqrt(sum(v * v for v in vector))
    return [v / magnitude for v in vector] if magnitude else vector


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    if len(a) != len(b):
        return 0.0
    return sum(x * y for x, y in zip(a, b))


def build_provider() -> EmbeddingProvider:
    """Neural when a key is configured, lexical otherwise. The pipeline is identical either way."""
    settings = get_settings()
    if settings.openai_api_key and not settings.stub_mode:
        return ApiEmbedding()
    return LexicalEmbedding()
