package com.s2s.context.local

/**
 * Answers "what did we discuss about X" without loading the whole transcript.
 *
 * Narrow on purpose: one method, a query in, ranked events out. [SqliteRetrieval]
 * backs it with FTS5 keyword search — no embeddings, no vector index. A future
 * semantic implementation (embeddings + a vector index) implements the same
 * interface; [WorkingContextBuilder] and [SqliteContextEngine] never know the
 * difference, since neither depends on how relevance is actually computed.
 */
internal interface Retrieval {
    /** Up to [limit] events from [sessionId]'s transcript most relevant to [query], ranked best-first. */
    fun search(sessionId: String, query: String, limit: Int): List<ConversationEvent>
}

internal class SqliteRetrieval(private val store: TranscriptStore) : Retrieval {
    override fun search(sessionId: String, query: String, limit: Int): List<ConversationEvent> =
        store.search(sessionId, query, limit)
}
