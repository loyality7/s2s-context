package com.s2s.context.local

import kotlin.math.abs
import kotlin.math.ln

/**
 * Orders candidate memories for prompt injection. Deterministic and
 * LLM-free by design: this runs inside the latency budget of a voice turn,
 * so an extra model call here would be paid on every single thing the user
 * says.
 *
 * SQLite already did the text matching (FTS rank, or LIKE). What it cannot
 * know is that a user's own stated preference outranks something a web page
 * claimed, or that a fact from this morning outranks one from March. That
 * weighting is what this adds.
 *
 * Deliberately simple arithmetic, not a learned scorer: every term is
 * inspectable, and a surprising ranking can be explained by reading four
 * numbers. Embeddings/vector similarity is the obvious future upgrade and
 * fits as an extra term without changing this shape.
 */
internal object MemoryRanker {

    /**
     * Scored candidates, best first, with weak matches dropped entirely.
     *
     * The floor matters as much as the ordering. SQLite's `LIKE` fallback
     * (and, to a lesser degree, FTS) will match a memory that merely shares
     * a common word with the question — so without a minimum overlap, asking
     * "what's the weather in Oslo?" pulls in "the user's favourite editor is
     * Vim" and every prompt quietly carries irrelevant memory. Returning
     * nothing is the correct answer to a question memory cannot help with.
     *
     * Ties broken by recency, then id, so ordering is stable across
     * identical inputs.
     */
    fun rank(candidates: List<Memory>, query: String, now: Long): List<Memory> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()
        return candidates
            .filter { isRelevant(it.content, queryTerms) }
            .map { it to score(it, queryTerms, now) }
            .sortedWith(
                compareByDescending<Pair<Memory, Float>> { it.second }
                    .thenByDescending { it.first.updatedAt }
                    .thenByDescending { it.first.memoryId },
            )
            .map { it.first }
    }

    /** Exposed for tests and for diagnostic tracing — a ranking nobody can explain is a ranking nobody can fix. */
    fun score(memory: Memory, queryTerms: Set<String>, now: Long): Float {
        val overlap = termOverlap(memory.content, queryTerms)
        val recency = recencyScore(memory.updatedAt, now)
        val trust = trustWeight(memory.provenance)

        return (W_OVERLAP * overlap) +
            (W_IMPORTANCE * memory.importance) +
            (W_RECENCY * recency) +
            (W_CONFIDENCE * memory.confidence) +
            (W_TRUST * trust)
    }

    /**
     * Whether a candidate is related to the question at all.
     *
     * Not a fraction threshold. A fraction punishes long questions unfairly:
     * "what response style do I prefer?" has three meaningful words, so a
     * memory matching only the decisive one ("prefer") scores 0.33 and would
     * be cut by any sensible-looking fraction floor — even though it is
     * exactly the right memory. Conversely a two-word question matching one
     * incidental word scores 0.5 and would pass.
     *
     * So the rule is about *word substance* instead: a match on a
     * reasonably long word is meaningful on its own, while short words
     * ("do", "is", "my") need company. This is the difference between
     * recalling a stored preference and injecting the user's editor
     * preference into a question about the weather.
     */
    private fun isRelevant(content: String, queryTerms: Set<String>): Boolean {
        val contentTerms = tokenize(content)
        if (contentTerms.isEmpty()) return false

        var substantialHits = 0
        var shortHits = 0
        for (term in queryTerms) {
            val hit = contentTerms.any { it == term || (term.length >= 4 && it.startsWith(term)) }
            if (!hit) continue
            if (term.length >= SUBSTANTIAL_TERM_CHARS) substantialHits++ else shortHits++
        }
        return substantialHits >= 1 || shortHits >= 2
    }

    /**
     * Fraction of query terms present in the content.
     *
     * Recomputed here even though SQLite already matched, because FTS rank
     * is not comparable across the two retrieval paths (FTS vs LIKE
     * fallback) — scoring in one place keeps ranking identical whether or
     * not this device's SQLite has FTS5.
     */
    private fun termOverlap(content: String, queryTerms: Set<String>): Float {
        if (queryTerms.isEmpty()) return 0f
        val contentTerms = tokenize(content)
        if (contentTerms.isEmpty()) return 0f
        val hits = queryTerms.count { term ->
            contentTerms.any { it == term || (term.length >= 4 && it.startsWith(term)) }
        }
        return hits.toFloat() / queryTerms.size
    }

    /**
     * 1.0 for "just now", decaying smoothly with age; never reaches zero, so
     * an old memory stays reachable rather than becoming unretrievable.
     * Logarithmic because the difference between today and yesterday matters
     * far more than between last year and the year before.
     */
    private fun recencyScore(updatedAt: Long, now: Long): Float {
        val ageDays = abs(now - updatedAt).toFloat() / MILLIS_PER_DAY
        return (1f / (1f + ln(1f + ageDays))).coerceIn(0f, 1f)
    }

    /**
     * How much a source is trusted for ranking purposes.
     *
     * This is only a ranking nudge — provenance gating (what may be written
     * at all, and what may claim to be a user fact) is enforced in
     * [MemoryWriter], not here. Ranking must never be the thing standing
     * between untrusted text and the prompt.
     */
    private fun trustWeight(provenance: MemoryProvenance): Float = when (provenance) {
        MemoryProvenance.USER -> 1.0f
        MemoryProvenance.SYSTEM -> 0.8f
        MemoryProvenance.AGENT -> 0.6f
        MemoryProvenance.TOOL -> 0.4f
        MemoryProvenance.EXTERNAL -> 0.2f
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            .toSet()

    private const val MILLIS_PER_DAY = 86_400_000f

    /**
     * A matched word this long is meaningful by itself.
     *
     * 5 characters: long enough that "prefer", "editor", "language",
     * "weather" qualify while "code"/"mode"/"time" do not. Not a linguistic
     * truth — a heuristic whose two failure modes are understood, and whose
     * safer direction (missing a recall) the user can correct by rephrasing,
     * unlike the other direction (noise in every prompt) which they cannot.
     */
    private const val SUBSTANTIAL_TERM_CHARS = 5

    // Weights sum to 1.0 so a score is readable as "how good, out of 1".
    private const val W_OVERLAP = 0.45f
    private const val W_IMPORTANCE = 0.20f
    private const val W_RECENCY = 0.15f
    private const val W_CONFIDENCE = 0.10f
    private const val W_TRUST = 0.10f

    /**
     * Small, deliberately incomplete stop list: enough that "what do I
     * prefer" doesn't match every memory on "what"/"do"/"i", without
     * pretending to be a real linguistic resource.
     */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "you", "your", "was", "were", "with", "that", "this",
        "have", "has", "had", "what", "when", "where", "which", "who", "why", "how",
        "did", "does", "do", "is", "it", "of", "to", "in", "on", "at", "my", "me", "am",
    )
}
