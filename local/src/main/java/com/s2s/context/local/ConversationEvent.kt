package com.s2s.context.local

/** What kind of thing happened in the conversation. */
enum class ConversationEventType {
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    SYSTEM_EVENT,
}

/**
 * One durable record in the full transcript. Every event this system ever
 * records is one of these — the transcript is the append-only ground truth;
 * everything else (working context, retrieval hits, memory) is a view over it.
 *
 * [eventId] is unique and monotonic within a session (assigned by
 * [TranscriptStore] on insert), so ordering and pagination never depend on
 * wall-clock timestamps, which can collide or skew.
 */
data class ConversationEvent(
    val eventId: Long,
    val sessionId: String,
    val type: ConversationEventType,
    val content: String,
    val timestamp: Long,
)
