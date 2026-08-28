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
    /** Null means "let the writer judge from the text". An explicit value wins. */
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
 * The gate between conversation and durable memory.
 *
 * Deliberately conservative. A personal agent that remembers everything is
 * not more helpful — it is a system whose recall is dominated by noise, and
 * whose prompt fills with things the user never asked it to keep. So the
 * default answer is [MemoryDecision.Ignored], and something has to earn a
 * write:
 *
 * - an **explicit** instruction ("remember that…", "I prefer…", "always…"),
 * - or a caller stating importance itself, which is how a tool/plugin with
 *   real knowledge (a calendar sync, say) records a fact without pretending
 *   the user said it.
 *
 * Provenance is enforced here, not in ranking: text from a tool or the web
 * may be *stored*, but never at [MemoryScope.User] and never at full
 * confidence. Ranking is a preference; this is a boundary. Without it, a
 * web page that says "the user prefers X" eventually becomes something the
 * assistant believes about its user.
 *
 * No LLM call. Runs on the write path, which may be on a background thread
 * but still shares a device with a voice turn — and an extraction model
 * here would be a second permanent model in the hot path. A model-assisted
 * extractor can be layered on top later by feeding it as another producer
 * of [MemoryCandidate]s.
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

        val explicit = isExplicitRequest(content)
        val importance = candidate.importance
            ?: if (explicit) IMPORTANCE_EXPLICIT else IMPORTANCE_INCIDENTAL

        // The gate: an episodic record is a factual log and may be written
        // by its producer, but a DURABLE belief needs either an explicit
        // user instruction or a caller willing to state importance.
        if (candidate.kind == MemoryKind.DURABLE &&
            !explicit &&
            candidate.importance == null &&
            candidate.provenance != MemoryProvenance.SYSTEM
        ) {
            return MemoryDecision.Ignored("ordinary conversation is not stored as durable memory unless asked")
        }

        // Dedup against what would actually be STORED, not against what the
        // user typed: "Remember that I prefer X" is stored as "I prefer X",
        // so comparing the raw phrasing would never match the existing row
        // and every repeat would create a new memory.
        val storable = stripLeadingRequest(content)

        repository.findDuplicate(candidate.scope, storable)?.let { existing ->
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
                content = storable,
                kind = candidate.kind,
                provenance = candidate.provenance,
                importance = importance,
                confidence = confidence,
                tags = candidate.tags,
            ),
        )
    }

    /**
     * Whether the user is asking for something to be remembered, rather
     * than just talking.
     *
     * Phrase matching, not a model: it is cheap, it is inspectable, and its
     * failure mode is the safe one — a missed cue means a memory is not
     * written, which the user can correct by saying it plainly. The
     * opposite failure (inventing memories from ordinary chat) is the one
     * that quietly ruins a personal agent.
     */
    private fun isExplicitRequest(content: String): Boolean {
        val lower = content.lowercase()
        return EXPLICIT_CUES.any { lower.startsWith(it) || lower.contains(" $it") }
    }

    /** Drops the "remember that" preamble so the stored memory reads as the fact itself, not as the request to store it. */
    private fun stripLeadingRequest(content: String): String {
        val lower = content.lowercase()
        for (prefix in STRIPPABLE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return content.substring(prefix.length).trimStart().replaceFirstChar { it.uppercase() }
            }
        }
        return content
    }

    private companion object {
        const val MIN_CONTENT_CHARS = 8
        const val MAX_CONTENT_CHARS = 500

        const val IMPORTANCE_EXPLICIT = 0.9f
        const val IMPORTANCE_INCIDENTAL = 0.4f

        val EXPLICIT_CUES = listOf(
            "remember that", "remember this", "remember:", "remember ",
            "don't forget", "do not forget",
            "i prefer", "i'd prefer", "i would prefer",
            "i always", "i never",
            "always ", "never ",
            "from now on", "in future", "in the future",
            "my name is", "call me",
            "note that", "keep in mind",
        )

        val STRIPPABLE_PREFIXES = listOf(
            "remember that ", "remember this: ", "remember: ", "remember ",
            "please remember that ", "please remember ",
            "note that ", "keep in mind that ",
            "don't forget that ", "do not forget that ",
        )
    }
}
