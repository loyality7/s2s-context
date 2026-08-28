package com.s2s.context.local

/**
 * One remembered item, expected to stay useful beyond the conversation that
 * produced it — "the user prefers metric units," not "the user just asked
 * about the weather." Distinct from [ConversationEvent]: the transcript is a
 * record of what was said, memory is what's worth remembering from it.
 *
 * [scope] decides who can see it, [provenance] decides how far it can be
 * trusted, and [kind] separates a durable fact from a record of something
 * that happened. Those three are the reason this is a structured row rather
 * than a blob of remembered text: retrieval that cannot tell a user's own
 * stated preference from something a web page claimed is not safe to inject
 * into a prompt.
 */
data class Memory(
    val memoryId: Long,
    val scope: MemoryScope,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val kind: MemoryKind = MemoryKind.DURABLE,
    val provenance: MemoryProvenance = MemoryProvenance.USER,
    /**
     * Rough "how much does this matter", 0..1. Used only for ranking, never
     * as a gate — a low-importance memory is still retrievable, it just
     * loses to a high-importance one competing for the same prompt slot.
     */
    val importance: Float = 0.5f,
    /**
     * How sure we are the content is true, 0..1. Distinct from [importance]:
     * "the user's birthday is in June" can be very important and only
     * half-confident. Kept separate so a guess is never promoted to a fact
     * just because it matters.
     */
    val confidence: Float = 1.0f,
    /** Free-form labels for filtering/inspection. Never parsed for meaning by the retrieval code. */
    val tags: List<String> = emptyList(),
    /** When this memory was last actually retrieved into a prompt — input to retention decisions, not to ranking. */
    val lastAccessedAt: Long? = null,
)

/**
 * Durable facts and episodic events are stored in one table but are NOT the
 * same thing, and conflating them is how a memory system starts replaying
 * history as if it were belief.
 */
enum class MemoryKind {
    /** A fact expected to remain true: a preference, a relationship, a decision. Answers "what is so?" */
    DURABLE,

    /**
     * A record that something happened at a time: a plugin was installed, a
     * build was tested, a tool failed. Answers "what happened?" — and stays
     * true as a historical record even when its subject is no longer current.
     */
    EPISODIC,
}

/**
 * Where a memory came from, and therefore how much authority it carries.
 *
 * This exists because a personal agent with tools and network access will
 * inevitably encounter text that *claims* something about its user. A
 * sentence the user typed and a sentence a web page returned must not be
 * remembered with equal trust, and must never be indistinguishable once
 * stored.
 */
enum class MemoryProvenance {
    /** The user said it themselves. The only source that may establish a user preference or profile fact outright. */
    USER,

    /** The assistant inferred or summarised it. Useful, but a conclusion — not testimony. */
    AGENT,

    /**
     * A tool returned it. Never sufficient on its own to assert something
     * about the user: tool output is data the user may not have seen, and
     * treating it as user testimony is exactly how memory poisoning works.
     */
    TOOL,

    /** The host/app recorded it (a setting change, a lifecycle event). */
    SYSTEM,

    /** Anything from outside — a web page, a message, an imported file. Lowest trust. */
    EXTERNAL,
}

/**
 * Who a memory belongs to. Scope is a visibility boundary, not a category:
 * getting it wrong turns a fact that was true of one project into a fact
 * the agent believes about the user in general.
 */
sealed interface MemoryScope {
    /** Visible only when retrieving for this session. */
    data class Session(val sessionId: String) : MemoryScope

    /**
     * Visible for any session — a fact true regardless of which conversation
     * asks. Retained for compatibility with memories written before scope
     * was subdivided; new user-level facts should prefer [User].
     */
    object Global : MemoryScope

    /** A stable fact about the user themselves. Visible across every session and project. */
    object User : MemoryScope

    /** Scoped to a named project/topic — visible only when that project is the one being asked about. */
    data class Project(val projectId: String) : MemoryScope

    /** Scoped to one unit of work; the shortest-lived scope, expected to be cleaned up when the task ends. */
    data class Task(val taskId: String) : MemoryScope
}

/**
 * Create/retrieve/update/delete for memory, plus relevance-ranked
 * retrieval so [WorkingContextBuilder] never has to inject every stored
 * memory into every prompt (see [Memory]'s doc for why that matters — the
 * whole point of memory is that it is retrieved selectively, not replayed
 * wholesale like a transcript).
 */
interface MemoryRepository {
    /** Writes a memory. Prefer [MemoryWriter] for anything derived from conversation — it applies the gating this does not. */
    fun create(
        scope: MemoryScope,
        content: String,
        kind: MemoryKind = MemoryKind.DURABLE,
        provenance: MemoryProvenance = MemoryProvenance.USER,
        importance: Float = 0.5f,
        confidence: Float = 1.0f,
        tags: List<String> = emptyList(),
    ): Memory

    fun get(memoryId: Long): Memory?
    fun update(memoryId: Long, content: String): Memory?
    fun delete(memoryId: Long)

    /**
     * Memories visible to [sessionId] relevant to [query], ranked best-first.
     *
     * [kinds] filters by [MemoryKind] — a "what do I prefer?" question wants
     * [MemoryKind.DURABLE], a "what did we do yesterday?" question wants
     * [MemoryKind.EPISODIC], and asking for both by default would let history
     * crowd out belief.
     *
     * [projectIds] widens visibility to those [MemoryScope.Project] scopes;
     * project memories are invisible unless explicitly asked for, so a fact
     * from one project never leaks into another.
     */
    fun relevant(
        sessionId: String,
        query: String,
        limit: Int,
        kinds: Set<MemoryKind> = setOf(MemoryKind.DURABLE),
        projectIds: Set<String> = emptySet(),
    ): List<Memory>

    /** Everything in [scope], newest first — for a memory-inspection UI, not for prompt injection. */
    fun list(scope: MemoryScope? = null, kind: MemoryKind? = null, limit: Int = 200): List<Memory>

    /** Removes every memory in [scope]. For "forget this project" / "clear my data", so a user can actually exercise control. */
    fun deleteScope(scope: MemoryScope)

    /** An existing memory with the same normalised content in the same scope, if any — the dedup lookup [MemoryWriter] uses before writing. */
    fun findDuplicate(scope: MemoryScope, content: String): Memory?

    /** Records that [memoryId] was retrieved, for retention decisions. Cheap and best-effort; never fails a retrieval. */
    fun touch(memoryId: Long, at: Long)
}
