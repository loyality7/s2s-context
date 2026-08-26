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
            memory.relevant(sessionId, currentUserText, config.relevantMemoryLimit)
        }

        return buildList {
            add(ChatMessage("system", listOfNotNull(systemPrompt, extraSystem).joinToString("\n\n")))

            if (relevantMemory.isNotEmpty()) {
                add(
                    ChatMessage(
                        "system",
                        "Remembered from prior context: " + relevantMemory.joinToString(" ") { it.content },
                    ),
                )
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
    /** Verbatim recent turns kept in every prompt, oldest to newest. */
    val recentEventLimit: Int = 12,
    /** Retrieved older events injected as a compact summary line, not verbatim. */
    val relevantHistoryLimit: Int = 3,
    /** Retrieved durable memories injected per turn. */
    val relevantMemoryLimit: Int = 3,
    /** Characters of a retrieved event's content shown in the summary line. */
    val retrievedSnippetChars: Int = 160,
)
