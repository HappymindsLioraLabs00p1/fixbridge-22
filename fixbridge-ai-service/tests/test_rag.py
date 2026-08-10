"""RAG tests.

The behaviour that matters most is the negative one: an unrelated query must retrieve nothing.
Grounding a repair plan in an irrelevant document is worse than not grounding it at all, because
the plan then looks sourced while being wrong.
"""

import pytest

from app.rag.embeddings import LexicalEmbedding, cosine, tokenize
from app.rag.retriever import RagAgent
from app.rag.store import InMemoryVectorStore, chunk_document


# ---- tokenisation & embedding ------------------------------------------------------------

def test_stopwords_and_short_words_are_dropped():
    tokens = tokenize("The water is leaking from a pipe")
    assert "the" not in tokens and "is" not in tokens
    assert "water" in tokens and "leaking" in tokens and "pipe" in tokens


def test_embedding_must_be_fitted_before_use():
    """Embedding an unfitted model would return zero vectors and silently match nothing."""
    with pytest.raises(RuntimeError):
        LexicalEmbedding().embed(["anything"])


def test_similar_text_scores_higher_than_unrelated_text():
    e = LexicalEmbedding()
    e.fit([
        "water leaking from the pipe connection under the sink",
        "the roof tiles have come loose in the wind",
        "washing machine will not drain the drum",
    ])
    query, leak, roof = e.embed([
        "my sink pipe is leaking water",
        "water leaking from the pipe connection under the sink",
        "the roof tiles have come loose in the wind",
    ])
    assert cosine(query, leak) > cosine(query, roof)


# ---- chunking ----------------------------------------------------------------------------

def test_chunking_splits_long_documents_without_breaking_paragraphs():
    # Each paragraph is ~30 words, so 20 of them comfortably exceed the 180-word chunk target.
    paragraph = ("This is a sufficiently long paragraph of instruction text describing part of a "
                 "repair procedure in enough detail that it carries real weight when the document "
                 "is divided into retrievable passages by the chunker.")
    text = "## Heading\n\n" + "\n\n".join(f"Step {i}. {paragraph}" for i in range(20))

    chunks = chunk_document(text, source="test.md", category="plumbing")

    assert len(chunks) > 1, "a long document should produce several chunks"
    for c in chunks:
        assert c.source == "test.md" and c.category == "plumbing"
    # Paragraphs are kept whole — half a safety warning is worse than none.
    assert all(paragraph not in c.text or c.text.count(paragraph) == c.text.count("Step")
               for c in chunks)


def test_chunks_record_their_section():
    text = "## Leak at a fitting\n\nSome instruction text that is long enough to be a paragraph here."
    chunks = chunk_document(text, source="plumbing.md", category="plumbing")
    assert chunks[0].section is not None


# ---- retrieval ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def agent():
    a = RagAgent(store=InMemoryVectorStore())
    a.ingest()
    return a


def test_the_corpus_is_ingested(agent):
    assert agent.store.count() > 0


def test_a_plumbing_question_retrieves_plumbing_knowledge(agent):
    g = agent.retrieve("water leaking from the pipe joint under my sink", category="plumbing")
    assert g.is_grounded
    assert all("plumbing" in s for s in g.sources)


def test_an_appliance_question_retrieves_appliance_knowledge(agent):
    g = agent.retrieve("washing machine will not drain, water still in the drum", category="appliance")
    assert g.is_grounded
    assert any("drain" in s.lower() for s in g.sources)


def test_an_unrelated_question_retrieves_nothing(agent):
    """The important negative: no grounding beats false grounding."""
    g = agent.retrieve("my dog keeps barking at the postman")
    assert not g.is_grounded
    assert g.passages == []


def test_retrieved_context_is_delimited_for_the_prompt(agent):
    """Retrieved text is untrusted. It must be fenced so a document cannot pose as an instruction."""
    g = agent.retrieve("leaking pipe under the sink", category="plumbing")
    block = g.as_prompt_block()
    assert "<<<KNOWLEDGE" in block and "<<<END>>>" in block
    assert "source=" in block


def test_every_passage_carries_a_source(agent):
    g = agent.retrieve("blocked drain", category="plumbing")
    assert len(g.sources) == len(g.passages) == len(g.scores)


# ---- planner integration -----------------------------------------------------------------

def test_a_plan_cites_retrieved_sources():
    from app.schemas.repair import SafetyAssessment, SafetyLevel
    from app.services.repair_planner import RepairPlanner

    plan = RepairPlanner().plan(
        "water leaking from the pipe joint under my sink", "plumbing",
        SafetyAssessment(level=SafetyLevel.SAFE_DIY, confidence=0.8))
    assert plan.sources
    assert any(".md" in s for s in plan.sources), "a grounded plan should cite its documents"


def test_a_plan_for_an_unknown_category_still_works():
    """Retrieval finding nothing must degrade to the curated fallback, not fail."""
    from app.schemas.repair import SafetyAssessment, SafetyLevel
    from app.services.repair_planner import RepairPlanner

    plan = RepairPlanner().plan("something unusual entirely", "general",
                                SafetyAssessment(level=SafetyLevel.SAFE_DIY, confidence=0.8))
    assert plan.steps and plan.sources
