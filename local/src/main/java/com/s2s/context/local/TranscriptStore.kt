package com.s2s.context.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Full, append-only conversation history. Nothing is ever deleted from here
 * by [WorkingContextBuilder] or [SqliteContextEngine.clear] alone —
 * [ConversationEvent]s persist across process death, session recreation, and
 * working-context compaction. Only [wipeSession] removes rows, and only
 * because the host asked to forget a conversation entirely.
 *
 * Backed by SQLite with an FTS5 virtual table for keyword retrieval — no
 * embeddings, no vector index. [Retrieval] is a thin query layer over this
 * store, not a separate storage mechanism, so there is exactly one place
 * transcript data can drift from what retrieval sees.
 */
internal class TranscriptStore(context: Context, dbName: String = "s2s_context.db") :
    SQLiteOpenHelper(context, dbName, null, DB_VERSION),
    SQLiteOpenHelperAccess {

    /**
     * True on every real Android device (FTS5 has shipped since API 11), but
     * some non-standard SQLite builds — notably Robolectric's shadow SQLite
     * used for JVM unit tests — omit it. Detected once, lazily, on first use
     * rather than assumed, so [search] degrades to a `LIKE` scan instead of
     * crashing wherever FTS5 genuinely isn't compiled in. Lazy (not decided
     * in onCreate/onOpen) because a brand-new database only runs onCreate,
     * while a reopened existing one only runs onOpen — computing it exactly
     * once in the right place for both cases is more fragile than computing
     * it the first time anything actually needs to know.
     */
    private val ftsAvailable: Boolean by lazy { probeFts() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_events_session ON events(session_id, event_id)")
        createFtsObjects(db)
        SqliteMemoryRepository.createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No prior version to migrate from yet.
    }

    /**
     * Best-effort: always attempted regardless of whether FTS5 actually
     * works on this SQLite build. If the module is missing, this either
     * throws (caught here, nothing created) or "succeeds" while creating a
     * table that fails on first real query — either way [probeFts] is the
     * source of truth for whether retrieval can actually use it, not this.
     */
    private fun createFtsObjects(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE events_fts USING fts5(
                    content,
                    content='events',
                    content_rowid='event_id'
                )
                """.trimIndent(),
            )
            // Keep the FTS index in sync with events without every caller
            // remembering to do it — a mismatch here would silently make
            // retrieval blind to rows the transcript store itself still has.
            db.execSQL(
                """
                CREATE TRIGGER events_ai AFTER INSERT ON events BEGIN
                    INSERT INTO events_fts(rowid, content) VALUES (new.event_id, new.content);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER events_ad AFTER DELETE ON events BEGIN
                    INSERT INTO events_fts(events_fts, rowid, content) VALUES ('delete', old.event_id, old.content);
                END
                """.trimIndent(),
            )
        }
    }

    /**
     * The real check: run the exact query shape [TranscriptStore.searchFts]
     * uses, on [readableDatabase] (not the [SQLiteDatabase] handed to
     * onCreate) — some SQLite builds accept `CREATE VIRTUAL TABLE` for an
     * unrecognized module without erroring and only fail on first real use,
     * and a query issued against a different connection than onCreate's can
     * behave differently again. Only this probe result is trusted.
     */
    private fun probeFts(): Boolean = runCatching {
        val db = writableDatabase
        db.execSQL("INSERT INTO events(session_id, type, content, timestamp) VALUES ('__probe__', 'SYSTEM_EVENT', 'probe', 0)")
        try {
            db.rawQuery(
                """
                SELECT e.event_id FROM events_fts f
                JOIN events e ON e.event_id = f.rowid
                WHERE f.events_fts MATCH 'probe' AND e.session_id = '__probe__'
                ORDER BY rank
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { it.moveToFirst() }
        } finally {
            db.execSQL("DELETE FROM events WHERE session_id = '__probe__'")
        }
    }.isSuccess

    override fun readable(): SQLiteDatabase = readableDatabase
    override fun writable(): SQLiteDatabase = writableDatabase
    override fun now(): Long = System.currentTimeMillis()
    override fun ftsAvailable(): Boolean = ftsAvailable

    fun append(sessionId: String, type: ConversationEventType, content: String, timestamp: Long): ConversationEvent {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("type", type.name)
            put("content", content)
            put("timestamp", timestamp)
        }
        val id = writableDatabase.insertOrThrow("events", null, values)
        return ConversationEvent(id, sessionId, type, content, timestamp)
    }

    /** Every event ever recorded for [sessionId], oldest first. The full transcript — never truncated by this call. */
    fun fullHistory(sessionId: String): List<ConversationEvent> {
        readableDatabase.rawQuery(
            "SELECT event_id, session_id, type, content, timestamp FROM events WHERE session_id = ? ORDER BY event_id ASC",
            arrayOf(sessionId),
        ).use { cursor ->
            val out = mutableListOf<ConversationEvent>()
            while (cursor.moveToNext()) out += cursor.toEvent()
            return out
        }
    }

    /** The most recent [limit] events for [sessionId], oldest first (so callers can append them verbatim to a prompt). */
    fun recent(sessionId: String, limit: Int): List<ConversationEvent> {
        readableDatabase.rawQuery(
            "SELECT event_id, session_id, type, content, timestamp FROM events WHERE session_id = ? ORDER BY event_id DESC LIMIT ?",
            arrayOf(sessionId, limit.toString()),
        ).use { cursor ->
            val out = mutableListOf<ConversationEvent>()
            while (cursor.moveToNext()) out += cursor.toEvent()
            return out.asReversed()
        }
    }

    /** Removes the most recently appended event for [sessionId] if it matches [type] — used to retract an unanswered user turn. */
    fun removeLastIf(sessionId: String, type: ConversationEventType) {
        readableDatabase.rawQuery(
            "SELECT event_id, type FROM events WHERE session_id = ? ORDER BY event_id DESC LIMIT 1",
            arrayOf(sessionId),
        ).use { cursor ->
            if (!cursor.moveToNext()) return
            if (cursor.getString(cursor.getColumnIndexOrThrow("type")) != type.name) return
            val id = cursor.getLong(cursor.getColumnIndexOrThrow("event_id"))
            writableDatabase.delete("events", "event_id = ?", arrayOf(id.toString()))
        }
    }

    /** Overwrites the content of the most recent event for [sessionId] if it matches [type] — used by replaceLastUser. */
    fun replaceLastIf(sessionId: String, type: ConversationEventType, newContent: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT event_id, type FROM events WHERE session_id = ? ORDER BY event_id DESC LIMIT 1",
            arrayOf(sessionId),
        ).use { cursor ->
            if (!cursor.moveToNext()) return false
            if (cursor.getString(cursor.getColumnIndexOrThrow("type")) != type.name) return false
            val id = cursor.getLong(cursor.getColumnIndexOrThrow("event_id"))
            val values = ContentValues().apply { put("content", newContent) }
            writableDatabase.update("events", values, "event_id = ?", arrayOf(id.toString()))
            return true
        }
    }

    /** Keyword search over [sessionId]'s transcript. See [Retrieval] for the caller-facing API. */
    fun search(sessionId: String, query: String, limit: Int): List<ConversationEvent> {
        if (query.isBlank()) return emptyList()
        return if (ftsAvailable) searchFts(sessionId, query, limit) else searchLike(sessionId, query, limit)
    }

    private fun searchFts(sessionId: String, query: String, limit: Int): List<ConversationEvent> {
        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return emptyList()
        readableDatabase.rawQuery(
            """
            SELECT e.event_id, e.session_id, e.type, e.content, e.timestamp
            FROM events_fts f
            JOIN events e ON e.event_id = f.rowid
            WHERE f.events_fts MATCH ? AND e.session_id = ?
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            arrayOf(sanitized, sessionId, limit.toString()),
        ).use { cursor ->
            val out = mutableListOf<ConversationEvent>()
            while (cursor.moveToNext()) out += cursor.toEvent()
            return out
        }
    }

    /**
     * Fallback when FTS5 is unavailable: matches if the content contains ANY
     * query token, no ranking. Weaker than FTS5 (no relevance ordering, no
     * multi-word phrase support) but keeps retrieval functional rather than
     * crashing on a SQLite build without the module compiled in.
     */
    private fun searchLike(sessionId: String, query: String, limit: Int): List<ConversationEvent> {
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val whereClause = tokens.joinToString(" OR ") { "content LIKE ?" }
        val args = arrayOf(sessionId) + tokens.map { "%${it.replace("%", "")}%" }.toTypedArray()
        readableDatabase.rawQuery(
            """
            SELECT event_id, session_id, type, content, timestamp FROM events
            WHERE session_id = ? AND ($whereClause)
            ORDER BY event_id DESC
            LIMIT ${limit}
            """.trimIndent(),
            args,
        ).use { cursor ->
            val out = mutableListOf<ConversationEvent>()
            while (cursor.moveToNext()) out += cursor.toEvent()
            return out
        }
    }

    /** Deletes every event for [sessionId]. The one intentional way to forget a conversation, distinct from working-context compaction. */
    fun wipeSession(sessionId: String) {
        writableDatabase.delete("events", "session_id = ?", arrayOf(sessionId))
    }

    private fun android.database.Cursor.toEvent() = ConversationEvent(
        eventId = getLong(getColumnIndexOrThrow("event_id")),
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        type = ConversationEventType.valueOf(getString(getColumnIndexOrThrow("type"))),
        content = getString(getColumnIndexOrThrow("content")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    companion object {
        private const val DB_VERSION = 1

        /**
         * FTS5 MATCH syntax treats `" ' ( ) * -` etc as query operators — a
         * user's own words containing one of these would throw or silently
         * change what's searched. Quoting each token as an FTS5 string
         * literal makes the search literal-keyword-only, which is exactly
         * what a "find prior mentions of X" query needs.
         */
        internal fun sanitizeFtsQuery(raw: String): String =
            raw.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
