package com.s2s.context.local

import com.s2s.mobile.pipeline.ChatMessage

/**
 * Turns the full transcript, retrieval, and memory into the bounded message
 * list a turn's [com.s2s.mobile.pipeline.LanguageModel.generate] call actually
 * receives — the "ModelContext" the architecture calls for, expressed as
 * [ChatMessage]s because that's the shape [LanguageModel] already accepts.
 *
 * Recent turns are kept verbatim up to [config]'s window; anything older is
 * NEVER deleted (see [TranscriptStore]) but also never sent by default —
 * instead [Retrieval] pulls back only the old turns relevant to what the user
 * just said, and [MemoryRepository] contributes durable facts the same way.
 * This is the actual fix for the "a month of messages" problem: bounding what
 * one prompt contains without bounding what the system remembers.
 */
internal class WorkingContextBuilder(
    private val transcript: TranscriptStore,
    private val retrieval: Retrieval,
    private val memory: MemoryRepository,
    private val config: WorkingContextConfig,
    private val identityStore: IdentityStore? = null,
) {
    /**
     * Builds the message list for [sessionId]. [currentUserText] — the most
     * recent user turn, already in [transcript] by the time this is called —
     * is used as the retrieval query, since it's the best available signal
     * for "what old information is relevant right now."
     */
    fun build(sessionId: String, systemPrompt: String, extraSystem: String?, currentUserText: String?): List<ChatMessage> {
        val recentEvents = transcript.recent(sessionId, config.recentEventLimit)
        val recentIds = recentEvents.map { it.eventId }.toSet()

        val relevantHistory = if (currentUserText.isNullOrBlank()) {
            emptyList()
        } else {
            retrieval.search(sessionId, currentUserText, config.relevantHistoryLimit)
                // Don't repeat something already in the verbatim recent window.
                .filterNot { it.eventId in recentIds }
        }

        val relevantMemory = if (currentUserText.isNullOrBlank()) {
            emptyList()
        } else {
            memory.relevant(
                sessionId = sessionId,
                query = currentUserText,
                limit = config.relevantMemoryLimit,
                kinds = config.retrievedMemoryKinds,
                projectIds = config.activeProjectIds,
            )
        }

        // Identity and profile are configuration the user chose, so they
        // belong in the system message with the host's own prompt — unlike
        // retrieved memory, which is data and gets quoted separately below.
        val identityFragment = identityStore?.loadIdentity()?.systemPromptFragment()
        val profileFragment = identityStore?.loadProfile()?.systemPromptFragment()

        return buildList {
            add(
                ChatMessage(
                    "system",
                    listOfNotNull(identityFragment, systemPrompt, profileFragment, extraSystem)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                ),
            )

            // Rendered by MemoryInjection, not concatenated here: retrieved
            // memory is quoted, attributed and budget-capped so a stored
            // sentence cannot read as an instruction the assistant was
            // given. See MemoryInjection's doc for why that matters.
            MemoryInjection.render(relevantMemory, config.memoryInjectionBudget)?.let {
                add(ChatMessage("system", it))
            }

            if (relevantHistory.isNotEmpty()) {
                add(
                    ChatMessage(
                        "system",
                        "Relevant earlier discussion: " + relevantHistory.joinToString(" ") { summarizeForPrompt(it) },
                    ),
                )
            }

            recentEvents.forEach { event -> add(event.toChatMessage()) }
        }
    }

    private fun summarizeForPrompt(event: ConversationEvent): String {
        val gist = event.content.trim().take(config.retrievedSnippetChars)
        return when (event.type) {
            ConversationEventType.USER_MESSAGE -> "User previously asked: $gist"
            ConversationEventType.ASSISTANT_MESSAGE -> "Assistant previously said: $gist"
            ConversationEventType.TOOL_RESULT -> "A tool previously returned: $gist"
            else -> gist
        }
    }

    private fun ConversationEvent.toChatMessage(): ChatMessage = when (type) {
        ConversationEventType.USER_MESSAGE -> ChatMessage("user", content)
        ConversationEventType.ASSISTANT_MESSAGE -> ChatMessage("assistant", content)
        ConversationEventType.TOOL_CALL -> ChatMessage("assistant", content)
        ConversationEventType.TOOL_RESULT -> ChatMessage("user", content)
        ConversationEventType.SYSTEM_EVENT -> ChatMessage("system", content)
    }
}

/**
 * How large the working context is allowed to get. All three limits bound
 * the PROMPT, never the underlying transcript or memory store — raising
 * these numbers costs more tokens per turn, never loses data.
 */
data class WorkingContextConfig(
    /**
     * Verbatim recent turns kept in every prompt, oldest to newest.
     *
     * Six, not twelve. Measured on a real phone: a ~2100-character prompt
     * took 15s to first token on a 0.5B local model, against ~580ms for a
     * ~70-character one — and prompt length is the variable the host
     * actually controls. Twelve turns of verbatim history is the largest
     * contributor to that length, and it also keeps shifting the prompt's
     * prefix as the window slides, which defeats KV-cache reuse and makes
     * every turn pay a full prefill instead of an incremental one.
     *
     * What this costs: the model sees less literal recent dialogue. What
     * offsets it: older turns are still retrievable ([relevantHistoryLimit])
     * and durable facts still surface through memory — bounding the prompt
     * is not the same as bounding what the system remembers.
     */
    val recentEventLimit: Int = 6,
    /** Retrieved older events injected as a compact summary line, not verbatim. */
    val relevantHistoryLimit: Int = 3,
    /** Retrieved durable memories injected per turn. */
    val relevantMemoryLimit: Int = 3,
    /** Characters of a retrieved event's content shown in the summary line. */
    val retrievedSnippetChars: Int = 160,
    /**
     * Which memory kinds ordinary retrieval considers.
     *
     * Durable only by default: episodic memory answers "what happened",
     * which is rarely what a question needs and would otherwise compete
     * with beliefs for the same few prompt slots. A host that wants
     * history-aware recall opts in by adding [MemoryKind.EPISODIC].
     */
    val retrievedMemoryKinds: Set<MemoryKind> = setOf(MemoryKind.DURABLE),
    /**
     * Projects whose [MemoryScope.Project] memories are currently visible.
     * Empty means none — a project fact never leaks into an unrelated
     * conversation just because it matched the words.
     */
    val activeProjectIds: Set<String> = emptySet(),
    /** Hard ceiling on what memory may add to one prompt. See [MemoryInjectionBudget]. */
    val memoryInjectionBudget: MemoryInjectionBudget = MemoryInjectionBudget(),
)
