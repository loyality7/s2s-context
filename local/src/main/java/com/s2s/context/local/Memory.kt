package com.s2s.context.local

/**
 * One durable fact, expected to stay useful beyond the conversation that
 * produced it — "the user prefers metric units," not "the user just asked
 * about the weather." Distinct from [ConversationEvent]: the transcript is a
 * record of what was said, memory is what's worth remembering from it.
 *
 * [scope] decides who can see it. Most memories are session-scoped (specific
 * to one conversation) — [MemoryScope.Global] is for the rarer fact that
 * should survive into a completely different conversation, which is why
 * scope is explicit here rather than inferred from context.
 */
data class Memory(
    val memoryId: Long,
    val scope: MemoryScope,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface MemoryScope {
    /** Visible only when retrieving for this session. */
    data class Session(val sessionId: String) : MemoryScope

    /** Visible when retrieving for any session — a fact true regardless of which conversation asks. */
    object Global : MemoryScope
}

/**
 * Create/retrieve/update/delete for durable memory, plus relevance-ranked
 * retrieval so [WorkingContextBuilder] never has to inject every stored
 * memory into every prompt (see [Memory]'s doc for why that matters — the
 * whole point of memory is that it is retrieved selectively, not replayed
 * wholesale like a transcript).
 */
interface MemoryRepository {
    fun create(scope: MemoryScope, content: String): Memory
    fun get(memoryId: Long): Memory?
    fun update(memoryId: Long, content: String): Memory?
    fun delete(memoryId: Long)

    /** Memories visible to [sessionId] (its own session-scoped memories plus every [MemoryScope.Global] one) relevant to [query], ranked best-first. */
    fun relevant(sessionId: String, query: String, limit: Int): List<Memory>
}
