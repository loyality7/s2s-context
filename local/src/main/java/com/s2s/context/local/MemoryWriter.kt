package com.s2s.context.local

/**
 * A proposed memory, before anything decides whether it is worth keeping.
 *
 * Separating "something happened that might be worth remembering" from "a
 * memory exists" is the whole point of this file: every sentence in a
 * conversation is a candidate, and almost none of them should become
 * permanent.
 */
data class MemoryCandidate(
    val content: String,
    val scope: MemoryScope,
    val provenance: MemoryProvenance,
    val kind: MemoryKind = MemoryKind.DURABLE,
    /**
     * Whether whoever produced this candidate already decided it is worth
     * keeping, rather than [consider] having to guess from the text.
     *
     * The intended caller is a `remember` tool call: the model's own
     * generation already chose to invoke it, which IS the judgment call —
     * asking [consider] to re-derive "did the user mean this?" from a phrase
     * list on top of that would be redundant at best. A caller that instead
     * hands every raw utterance to [consider] unconditionally must leave
     * this false; [consider] applies no independent judgment of its own
     * (see this class's doc for why guessing from substrings was removed).
     */
    val explicit: Boolean = false,
    /** Null means "let the writer judge from [explicit]". An explicit value wins outright. */
    val importance: Float? = null,
    val confidence: Float = 1.0f,
    val tags: List<String> = emptyList(),
)

/** What the writer decided, and why — returned rather than logged so a caller (or a test) can assert on the decision instead of guessing from side effects. */
sealed interface MemoryDecision {
    data class Stored(val memory: Memory) : MemoryDecision
    /** Content matched something already known in this scope; the existing row's timestamp moved instead of a duplicate being created. */
    data class Duplicate(val existing: Memory) : MemoryDecision
    data class Updated(val memory: Memory) : MemoryDecision
    data class Ignored(val reason: String) : MemoryDecision
}

/**
 * The gate between a memory request and durable storage.
 *
 * Deliberately conservative, but the judgment of "is this worth keeping" is
 * NOT this class's job — it used to guess from a hardcoded phrase list
 * (`EXPLICIT_CUES`: "remember that", "I prefer", "always", …), and a real
 * device caught exactly why that fails: the sentence "Hello cookies,
 * remember this" was stored verbatim, because it happened to contain the
 * substring "remember this" — the list matched syntax, not intent. A phrase
 * list cannot tell "remember that I like concise answers" (a real
 * preference) from "remember this" said about nothing in particular.
 *
 * The real judgment now belongs to whoever calls [consider]: normally a
 * `remember` tool the model itself chooses to invoke (see `s2s-tools`'
 * `MemoryTools`) — the model's own generation, already reasoning about the
 * conversation, decides intent for free, with no second LLM call and no
 * heuristic standing in for understanding. [MemoryCandidate.explicit] just
 * carries that decision through; this class enforces the boundaries around
 * it (dedup, provenance, scope) that no amount of caller good faith should
 * bypass:
 *
 * - Provenance is enforced here, not in ranking: text from a tool or the web
 *   may be *stored*, but never at [MemoryScope.User] and never at full
 *   confidence. Ranking is a preference; this is a boundary. Without it, a
 *   web page that says "the user prefers X" eventually becomes something the
 *   assistant believes about its user.
 * - A DURABLE memory still needs [MemoryCandidate.explicit], an explicit
 *   [MemoryCandidate.importance], or [MemoryProvenance.SYSTEM] — an ordinary
 *   caller that runs every utterance through [consider] unconditionally and
 *   never sets `explicit` stores nothing, by construction, not by luck.
 * - Duplicate detection and confidence capping run exactly as before.
 *
 * No LLM call. Runs on the write path, which may be on a background thread
 * but still shares a device with a voice turn.
 */
class MemoryWriter(
    private val repository: MemoryRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Considers one candidate. Never throws for ordinary rejection — a
     * candidate not being worth keeping is the normal case, not an error.
     */
    fun consider(candidate: MemoryCandidate): MemoryDecision {
        val content = candidate.content.trim()

        if (content.length < MIN_CONTENT_CHARS) return MemoryDecision.Ignored("too short to be meaningful")
        if (content.length > MAX_CONTENT_CHARS) return MemoryDecision.Ignored("too long; summarise before storing")

        // Provenance boundary. A non-user source may contribute knowledge,
        // but it may not author a fact about the user.
        if (candidate.scope == MemoryScope.User && candidate.provenance != MemoryProvenance.USER) {
            return MemoryDecision.Ignored(
                "only USER-provenance content may be stored at USER scope (was ${candidate.provenance})",
            )
        }

        val importance = candidate.importance
            ?: if (candidate.explicit) IMPORTANCE_EXPLICIT else IMPORTANCE_INCIDENTAL

        // The gate: an episodic record is a factual log and may be written
        // by its producer, but a DURABLE belief needs either the caller
        // marking it explicit or stating importance itself.
        if (candidate.kind == MemoryKind.DURABLE &&
            !candidate.explicit &&
            candidate.importance == null &&
            candidate.provenance != MemoryProvenance.SYSTEM
        ) {
            return MemoryDecision.Ignored("not marked explicit and no importance given — ordinary conversation is not stored as durable memory")
        }

        repository.findDuplicate(candidate.scope, content)?.let { existing ->
            // Seeing the same thing again is evidence it matters, so the
            // existing row is refreshed — but a repeat must not multiply
            // rows, or a user who says "I prefer Kotlin" three times ends
            // up with three memories competing for the same prompt slot.
            repository.touch(existing.memoryId, now())
            return MemoryDecision.Duplicate(existing)
        }

        val confidence = when (candidate.provenance) {
            MemoryProvenance.USER, MemoryProvenance.SYSTEM -> candidate.confidence
            // Unverified sources are capped regardless of what the caller claims.
            MemoryProvenance.AGENT -> candidate.confidence.coerceAtMost(0.7f)
            MemoryProvenance.TOOL -> candidate.confidence.coerceAtMost(0.6f)
            MemoryProvenance.EXTERNAL -> candidate.confidence.coerceAtMost(0.4f)
        }

        return MemoryDecision.Stored(
            repository.create(
                scope = candidate.scope,
                content = content,
                kind = candidate.kind,
                provenance = candidate.provenance,
                importance = importance,
                confidence = confidence,
                tags = candidate.tags,
            ),
        )
    }

    private companion object {
        const val MIN_CONTENT_CHARS = 8
        const val MAX_CONTENT_CHARS = 500

        const val IMPORTANCE_EXPLICIT = 0.9f
        const val IMPORTANCE_INCIDENTAL = 0.4f
    }
}
