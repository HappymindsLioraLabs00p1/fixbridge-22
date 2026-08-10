"""Chunking and the vector store.

The store is behind an interface with two implementations: in-memory, and pgvector. The retrieval
logic is identical for both, so moving to pgvector is configuration rather than a rewrite.

The knowledge base is reference data, not FixBridge business state — it holds repair procedures, not
customers or jobs — so keeping it out of the Java-owned tables does not violate the rule that Java
owns business state.
"""

from __future__ import annotations

import hashlib
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional, Sequence

import structlog

from app.rag.embeddings import EmbeddingProvider, cosine

log = structlog.get_logger()


@dataclass
class Chunk:
    """A retrievable passage. `source` and `section` travel with it so every claim in a repair plan
    can be traced back to the document it came from."""

    id: str
    text: str
    source: str
    category: str
    section: Optional[str] = None
    embedding: List[float] = field(default_factory=list)


@dataclass
class Retrieved:
    chunk: Chunk
    score: float


def chunk_document(text: str, source: str, category: str,
                   target_words: int = 180, overlap_words: int = 40) -> List[Chunk]:
    """Split on paragraph boundaries, packing to roughly `target_words`.

    Splitting mid-instruction is the failure mode to avoid: half a safety warning is worse than
    none, so paragraphs are kept whole and overlap carries context across the seam.
    """
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    chunks: List[Chunk] = []
    buffer: List[str] = []
    section: Optional[str] = None

    def flush() -> None:
        if not buffer:
            return
        body = "\n\n".join(buffer)
        chunks.append(Chunk(
            id=hashlib.sha256(f"{source}:{len(chunks)}:{body[:80]}".encode()).hexdigest()[:16],
            text=body, source=source, category=category, section=section))

    for paragraph in paragraphs:
        # A short line ending without punctuation reads as a heading; keep it as the section label.
        if len(paragraph) < 80 and not paragraph.endswith((".", ":", "!", "?")):
            section = paragraph
        buffer.append(paragraph)
        if sum(len(p.split()) for p in buffer) >= target_words:
            flush()
            # Carry the tail forward so a procedure spanning the boundary stays retrievable.
            tail, words = [], 0
            for p in reversed(buffer):
                tail.insert(0, p)
                words += len(p.split())
                if words >= overlap_words:
                    break
            buffer = tail
    flush()
    return chunks


class VectorStore(ABC):
    @abstractmethod
    def add(self, chunks: Sequence[Chunk]) -> None: ...

    @abstractmethod
    def search(self, query_embedding: Sequence[float], limit: int,
               category: Optional[str] = None) -> List[Retrieved]: ...

    @abstractmethod
    def count(self) -> int: ...


class InMemoryVectorStore(VectorStore):
    """Exhaustive cosine search. Linear in corpus size, which is the right trade for a curated
    knowledge base of hundreds of chunks — an index would add moving parts for no gain here."""

    def __init__(self) -> None:
        self._chunks: List[Chunk] = []

    def add(self, chunks: Sequence[Chunk]) -> None:
        self._chunks.extend(chunks)

    def search(self, query_embedding: Sequence[float], limit: int,
               category: Optional[str] = None) -> List[Retrieved]:
        candidates = [c for c in self._chunks if category is None or c.category == category]
        # Falling back to the whole corpus matters: a misclassified query should still retrieve
        # something relevant rather than nothing at all.
        if not candidates:
            candidates = self._chunks
        scored = [Retrieved(chunk=c, score=cosine(query_embedding, c.embedding)) for c in candidates]
        scored.sort(key=lambda r: r.score, reverse=True)
        return [r for r in scored[:limit] if r.score > 0]

    def count(self) -> int:
        return len(self._chunks)


class PgVectorStore(VectorStore):
    """PostgreSQL + pgvector. Used when KNOWLEDGE_DATABASE_URL is configured.

    Deliberately writes to its own table in its own schema — this is reference data, and it must not
    sit among FixBridge's business tables.
    """

    def __init__(self, dsn: str, dimensions: int) -> None:
        import psycopg  # imported lazily so the dependency is only needed when this store is used

        self.dsn = dsn
        self.dimensions = dimensions
        self._psycopg = psycopg
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        with self._psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
                cur.execute("CREATE SCHEMA IF NOT EXISTS fixbridge_knowledge")
                cur.execute(f"""
                    CREATE TABLE IF NOT EXISTS fixbridge_knowledge.chunks (
                      id        text PRIMARY KEY,
                      text      text NOT NULL,
                      source    text NOT NULL,
                      category  text NOT NULL,
                      section   text,
                      embedding vector({self.dimensions})
                    )""")
                cur.execute("CREATE INDEX IF NOT EXISTS idx_chunks_category "
                            "ON fixbridge_knowledge.chunks(category)")
            conn.commit()

    def add(self, chunks: Sequence[Chunk]) -> None:
        with self._psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                for c in chunks:
                    cur.execute(
                        "INSERT INTO fixbridge_knowledge.chunks "
                        "(id, text, source, category, section, embedding) "
                        "VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT (id) DO UPDATE "
                        "SET text=EXCLUDED.text, embedding=EXCLUDED.embedding",
                        (c.id, c.text, c.source, c.category, c.section, str(c.embedding)))
            conn.commit()

    def search(self, query_embedding: Sequence[float], limit: int,
               category: Optional[str] = None) -> List[Retrieved]:
        sql = ("SELECT id, text, source, category, section, 1 - (embedding <=> %s::vector) AS score "
               "FROM fixbridge_knowledge.chunks ")
        params: list = [str(list(query_embedding))]
        if category:
            sql += "WHERE category = %s "
            params.append(category)
        sql += "ORDER BY embedding <=> %s::vector LIMIT %s"
        params.extend([str(list(query_embedding)), limit])

        with self._psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                return [
                    Retrieved(chunk=Chunk(id=r[0], text=r[1], source=r[2], category=r[3], section=r[4]),
                              score=float(r[5]))
                    for r in cur.fetchall()
                ]

    def count(self) -> int:
        with self._psycopg.connect(self.dsn) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT count(*) FROM fixbridge_knowledge.chunks")
                return int(cur.fetchone()[0])


def build_store(dimensions: int) -> VectorStore:
    settings = get_settings_safe()
    dsn = getattr(settings, "knowledge_database_url", "")
    if dsn:
        try:
            log.info("vector_store", kind="pgvector")
            return PgVectorStore(dsn, dimensions)
        except Exception as exc:
            # A knowledge base that fails to connect must not take the service down — retrieval
            # degrades to in-memory and the assistant keeps working.
            log.warning("pgvector_unavailable_falling_back", error=str(exc))
    log.info("vector_store", kind="in-memory")
    return InMemoryVectorStore()


def get_settings_safe():
    from app.core.config import get_settings
    return get_settings()
