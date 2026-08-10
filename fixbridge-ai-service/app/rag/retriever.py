"""The RAG agent: ingest the knowledge base, then retrieve grounded context for a query.

The point of this layer is that repair instructions come from documents FixBridge controls rather
than from a model's recollection. A model asked to write plumbing steps produces fluent prose that
is occasionally wrong in ways a homeowner cannot detect, and here "wrong" means water damage. So
retrieval is the source of truth and the model's role is selection and phrasing.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

import structlog

from app.rag.embeddings import EmbeddingProvider, LexicalEmbedding, build_provider
from app.rag.store import Chunk, Retrieved, VectorStore, build_store, chunk_document

log = structlog.get_logger()

KNOWLEDGE_DIR = Path(__file__).resolve().parents[2] / "knowledge"

# Below this, retrieved text is more likely noise than context. Returning nothing is the honest
# outcome — it lets the planner fall back rather than grounding a plan in an unrelated document.
MIN_SCORE = 0.05


@dataclass
class GroundedContext:
    """What retrieval found, with sources so a plan can cite where each claim came from."""

    passages: List[str]
    sources: List[str]
    scores: List[float]

    @property
    def is_grounded(self) -> bool:
        return bool(self.passages)

    def as_prompt_block(self) -> str:
        """Formatted for a prompt, clearly delimited.

        The delimiters are not decoration: retrieved text is untrusted input, and the model must be
        able to tell knowledge from instructions so a document cannot rewrite the system prompt.
        """
        parts = []
        for passage, source in zip(self.passages, self.sources):
            parts.append(f"<<<KNOWLEDGE source=\"{source}\">>>\n{passage}\n<<<END>>>")
        return "\n\n".join(parts)


class RagAgent:
    def __init__(self, embedder: Optional[EmbeddingProvider] = None,
                 store: Optional[VectorStore] = None) -> None:
        self.embedder = embedder or build_provider()
        self._store = store
        self._ingested = False

    @property
    def store(self) -> VectorStore:
        if self._store is None:
            # Dimensions are only known after fitting, so the store is built on first use.
            self._store = build_store(dimensions=self._dimensions())
        return self._store

    def _dimensions(self) -> int:
        if isinstance(self.embedder, LexicalEmbedding):
            return max(len(self.embedder.vocabulary), 1)
        return 1536

    def ingest(self, directory: Optional[Path] = None) -> int:
        """Read the corpus, chunk it, embed it and store it. Idempotent."""
        if self._ingested:
            return self.store.count()

        source_dir = directory or KNOWLEDGE_DIR
        if not source_dir.exists():
            log.warning("knowledge_directory_missing", path=str(source_dir))
            self._ingested = True
            return 0

        chunks: List[Chunk] = []
        for path in sorted(source_dir.glob("*.md")):
            # The filename is the category — plumbing.md holds plumbing procedures.
            category = path.stem.lower()
            chunks.extend(chunk_document(path.read_text(), source=path.name, category=category))

        if not chunks:
            self._ingested = True
            return 0

        # A lexical embedding has to learn the vocabulary before anything can be embedded.
        if isinstance(self.embedder, LexicalEmbedding):
            self.embedder.fit([c.text for c in chunks])

        for chunk, vector in zip(chunks, self.embedder.embed([c.text for c in chunks])):
            chunk.embedding = vector

        self.store.add(chunks)
        self._ingested = True
        log.info("knowledge_ingested", chunks=len(chunks),
                 sources=len({c.source for c in chunks}), embedder=self.embedder.name)
        return len(chunks)

    def retrieve(self, query: str, category: Optional[str] = None, limit: int = 4) -> GroundedContext:
        """Find passages relevant to the query. Empty when nothing clears the score floor."""
        self.ingest()
        if self.store.count() == 0:
            return GroundedContext([], [], [])

        vector = self.embedder.embed([query])[0]
        hits: List[Retrieved] = self.store.search(vector, limit=limit, category=category)
        useful = [h for h in hits if h.score >= MIN_SCORE]

        log.info("knowledge_retrieved", query=query[:60], category=category,
                 hits=len(hits), kept=len(useful),
                 top_score=round(useful[0].score, 3) if useful else 0.0)

        return GroundedContext(
            passages=[h.chunk.text for h in useful],
            sources=[f"{h.chunk.source}{f' — {h.chunk.section}' if h.chunk.section else ''}"
                     for h in useful],
            scores=[round(h.score, 4) for h in useful],
        )
