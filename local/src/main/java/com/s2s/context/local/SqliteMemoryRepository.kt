package com.s2s.context.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * [MemoryRepository] backed by the same SQLite database [TranscriptStore]
 * uses — one file, two independent tables. `scope_session_id` is null for
 * [MemoryScope.Global] memories; a query for session S matches rows where
 * `scope_session_id = S OR scope_session_id IS NULL`.
 */
internal class SqliteMemoryRepository(private val db: SQLiteOpenHelperAccess) : MemoryRepository {

    override fun create(scope: MemoryScope, content: String): Memory {
        val now = db.now()
        val values = ContentValues().apply {
            put("scope_session_id", (scope as? MemoryScope.Session)?.sessionId)
            put("content", content)
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db.writable().insertOrThrow("memories", null, values)
        return Memory(id, scope, content, now, now)
    }

    override fun get(memoryId: Long): Memory? =
        db.readable().rawQuery(
            "SELECT memory_id, scope_session_id, content, created_at, updated_at FROM memories WHERE memory_id = ?",
            arrayOf(memoryId.toString()),
        ).use { cursor -> if (cursor.moveToNext()) cursor.toMemory() else null }

    override fun update(memoryId: Long, content: String): Memory? {
        val existing = get(memoryId) ?: return null
        val now = db.now()
        val values = ContentValues().apply {
            put("content", content)
            put("updated_at", now)
        }
        db.writable().update("memories", values, "memory_id = ?", arrayOf(memoryId.toString()))
        return existing.copy(content = content, updatedAt = now)
    }

    override fun delete(memoryId: Long) {
        db.writable().delete("memories", "memory_id = ?", arrayOf(memoryId.toString()))
    }

    override fun relevant(sessionId: String, query: String, limit: Int): List<Memory> {
        if (query.isBlank()) return emptyList()
        return if (db.ftsAvailable()) relevantFts(sessionId, query, limit) else relevantLike(sessionId, query, limit)
    }

    private fun relevantFts(sessionId: String, query: String, limit: Int): List<Memory> {
        val sanitized = TranscriptStore.sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return emptyList()
        db.readable().rawQuery(
            """
            SELECT m.memory_id, m.scope_session_id, m.content, m.created_at, m.updated_at
            FROM memories_fts f
            JOIN memories m ON m.memory_id = f.rowid
            WHERE f.memories_fts MATCH ?
              AND (m.scope_session_id = ? OR m.scope_session_id IS NULL)
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            arrayOf(sanitized, sessionId, limit.toString()),
        ).use { cursor ->
            val out = mutableListOf<Memory>()
            while (cursor.moveToNext()) out += cursor.toMemory()
            return out
        }
    }

    /** See [TranscriptStore]'s `searchLike` — same fallback, same tradeoffs, used when FTS5 isn't available. */
    private fun relevantLike(sessionId: String, query: String, limit: Int): List<Memory> {
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val whereClause = tokens.joinToString(" OR ") { "content LIKE ?" }
        val args = arrayOf(sessionId) + tokens.map { "%${it.replace("%", "")}%" }.toTypedArray()
        db.readable().rawQuery(
            """
            SELECT memory_id, scope_session_id, content, created_at, updated_at FROM memories
            WHERE (scope_session_id = ? OR scope_session_id IS NULL) AND ($whereClause)
            ORDER BY memory_id DESC
            LIMIT $limit
            """.trimIndent(),
            args,
        ).use { cursor ->
            val out = mutableListOf<Memory>()
            while (cursor.moveToNext()) out += cursor.toMemory()
            return out
        }
    }

    private fun android.database.Cursor.toMemory(): Memory {
        val sessionId = getString(getColumnIndexOrThrow("scope_session_id"))
        return Memory(
            memoryId = getLong(getColumnIndexOrThrow("memory_id")),
            scope = if (sessionId == null) MemoryScope.Global else MemoryScope.Session(sessionId),
            content = getString(getColumnIndexOrThrow("content")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        )
    }

    companion object {
        /**
         * Always attempts to create the FTS5 objects — best-effort, same
         * reasoning as [TranscriptStore.createFtsObjects]. Whether it
         * actually worked is decided at query time by [relevant], via
         * [SQLiteOpenHelperAccess.ftsAvailable].
         */
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE memories (
                    memory_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope_session_id TEXT,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            runCatching {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE memories_fts USING fts5(
                        content,
                        content='memories',
                        content_rowid='memory_id'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
                        INSERT INTO memories_fts(rowid, content) VALUES (new.memory_id, new.content);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.memory_id, old.content);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER memories_au AFTER UPDATE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.memory_id, old.content);
                        INSERT INTO memories_fts(rowid, content) VALUES (new.memory_id, new.content);
                    END
                    """.trimIndent(),
                )
            }
        }
    }
}

/**
 * Narrow seam [SqliteMemoryRepository] needs from the shared database —
 * avoids a direct dependency on [TranscriptStore]'s full surface, and gives
 * memory its own clock so tests can fake "time passing" without touching
 * SQLite at all.
 */
internal interface SQLiteOpenHelperAccess {
    fun readable(): SQLiteDatabase
    fun writable(): SQLiteDatabase
    fun now(): Long
    /** Whether the underlying SQLite build has the FTS5 module compiled in. See [TranscriptStore]'s doc for why this can be false. */
    fun ftsAvailable(): Boolean
}
