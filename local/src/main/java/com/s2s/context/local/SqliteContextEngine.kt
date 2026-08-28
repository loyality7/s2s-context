package com.s2s.context.local

import android.content.Context
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.ContextEngine
import org.json.JSONObject

/**
 * [ContextEngine] backed by SQLite: full transcript persisted forever,
 * relevant old history and durable memory retrieved by keyword search into a
 * bounded working context, so a long-running conversation never requires
 * sending its whole history to the model.
 *
 * One instance per conversation, same as [com.s2s.mobile.llm.ChatHistory] was
 * — `sessionId` is fixed at construction, not passed per-call, because
 * [com.s2s.mobile.S2SEngine] already scopes one [ContextEngine] to one
 * session (see the published core contract's own doc comment).
 */
class SqliteContextEngine(
    context: Context,
    private val sessionId: String,
    systemPrompt: String,
    private val workingContextConfig: WorkingContextConfig = WorkingContextConfig(),
) : ContextEngine {

    private val store = TranscriptStore(context.applicationContext)
    private val retrieval: Retrieval = SqliteRetrieval(store)
    private val memory: MemoryRepository = SqliteMemoryRepository(store)
    private val identity: IdentityStore = SqliteIdentityStore(store)
    private val builder = WorkingContextBuilder(store, retrieval, memory, workingContextConfig, identity)

    @Volatile
    private var system: String = systemPrompt

    /** Most recent user text appended, used as the retrieval query for [messages]. Not persisted separately — it's already in [store]. */
    @Volatile
    private var lastUserText: String? = null

    /** Durable memory CRUD — not part of [ContextEngine], since core has no opinion on memory management, only on what a turn's prompt looks like. */
    val memories: MemoryRepository get() = memory

    /**
     * Agent identity and user profile, persisted in this same database.
     *
     * Exposed here (not through [ContextEngine]) for the same reason
     * [memories] is: the generic contract cares only about what a turn's
     * prompt looks like. A host settings screen uses this; the agent
     * harness never touches it.
     */
    val identities: IdentityStore get() = identity

    /**
     * The gate every conversation-derived memory should go through.
     *
     * Exposed rather than applied automatically because *when* to consider
     * a memory is the host's decision — doing it inline on every turn would
     * put write work on the voice path, which §12's background-consolidation
     * requirement exists to avoid.
     */
    val memoryWriter: MemoryWriter by lazy { MemoryWriter(memory) }

    override fun addUser(text: String) {
        store.append(sessionId, ConversationEventType.USER_MESSAGE, text, System.currentTimeMillis())
        lastUserText = text
    }

    override fun replaceLastUser(text: String) {
        if (!store.replaceLastIf(sessionId, ConversationEventType.USER_MESSAGE, text)) {
            addUser(text)
            return
        }
        lastUserText = text
    }

    override fun addAssistant(text: String) {
        store.append(sessionId, ConversationEventType.ASSISTANT_MESSAGE, text, System.currentTimeMillis())
    }

    override fun dropLastUserIfUnanswered() {
        store.removeLastIf(sessionId, ConversationEventType.USER_MESSAGE)
    }

    override fun addToolResult(name: String, output: String) {
        store.append(sessionId, ConversationEventType.TOOL_RESULT, "[tool $name returned] $output", System.currentTimeMillis())
    }

    override fun messages(extraSystem: String?): List<ChatMessage> =
        builder.build(sessionId, system, extraSystem, lastUserText)

    override fun setSystemPrompt(prompt: String) {
        system = prompt
    }

    /**
     * Clears working-context state only — [store]'s transcript for this
     * session is untouched, per the architecture's core rule that context
     * limits are never solved by deleting history. Use
     * [wipeSessionCompletely] to actually forget a conversation.
     */
    override fun clear() {
        lastUserText = null
    }

    /** Deletes this session's entire transcript. The one real "forget everything" operation — distinct from [clear]. */
    fun wipeSessionCompletely() {
        store.wipeSession(sessionId)
        lastUserText = null
    }

    /**
     * Closes the underlying [android.database.sqlite.SQLiteOpenHelper]'s
     * connection — [store] extends it and inherits `close()`, but nothing
     * called it before this override existed, which is exactly why
     * `SQLiteConnectionPool` logged a leaked-connection warning whenever a
     * host stopped its runtime without recreating the process. Safe to call
     * more than once; [android.database.sqlite.SQLiteOpenHelper.close] is
     * idempotent.
     */
    override fun close() {
        store.close()
    }

    /**
     * SQLite already persists across process death — this only needs to
     * carry the small amount of state that lives outside it (the system
     * prompt and the retrieval-query cursor), so a restored engine picks up
     * exactly where the old one left off.
     *
     * [sessionId] is included for the host's own bookkeeping, but restoring
     * into a *different* session than the one this was saved from is a
     * mistake: [sessionId] is fixed at construction, not restored, so the
     * caller must construct the new [SqliteContextEngine] with the same
     * `sessionId` this JSON was saved under, or [fromJson] silently attaches
     * the saved system prompt to whatever session the new instance was
     * actually constructed for while the transcript stays with the original.
     */
    override fun toJson(): String = JSONObject().apply {
        put("sessionId", sessionId)
        put("system", system)
        put("lastUserText", lastUserText)
    }.toString()

    override fun fromJson(json: String) {
        val root = JSONObject(json)
        check(!root.has("sessionId") || root.getString("sessionId") == sessionId) {
            "fromJson() was given state saved under session '${root.optString("sessionId")}' " +
                "but this SqliteContextEngine was constructed for session '$sessionId' — " +
                "construct it with the same sessionId the JSON was saved from."
        }
        if (root.has("system")) system = root.getString("system")
        lastUserText = if (root.has("lastUserText") && !root.isNull("lastUserText")) {
            root.getString("lastUserText")
        } else {
            null
        }
    }
}
