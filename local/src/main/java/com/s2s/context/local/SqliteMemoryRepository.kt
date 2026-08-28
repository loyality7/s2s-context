package com.s2s.context.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * [MemoryRepository] backed by the same SQLite database [TranscriptStore]
 * uses — one file, several tables, deliberately not a second database.
 *
 * Scope is stored as (`scope_kind`, `scope_key`) rather than one nullable
 * session column, because scope is now a real visibility boundary with five
 * cases rather than "session or not". A `scope_kind` the running code does
 * not recognise is skipped at read time rather than guessed at — a forward
 * compatibility choice, so a downgrade cannot silently widen a narrow scope.
 */
internal class SqliteMemoryRepository(private val db: SQLiteOpenHelperAccess) : MemoryRepository {

    override fun create(
        scope: MemoryScope,
        content: String,
        kind: MemoryKind,
        provenance: MemoryProvenance,
        importance: Float,
        confidence: Float,
        tags: List<String>,
    ): Memory {
        val now = db.now()
        val values = ContentValues().apply {
            put("scope_kind", scope.kindName())
            put("scope_key", scope.key())
            // Kept in sync for any older code/query still reading it; the
            // (scope_kind, scope_key) pair is the source of truth.
            put("scope_session_id", (scope as? MemoryScope.Session)?.sessionId)
            put("content", content)
            put("content_norm", normalize(content))
            put("kind", kind.name)
            put("provenance", provenance.name)
            put("importance", importance.coerceIn(0f, 1f))
            put("confidence", confidence.coerceIn(0f, 1f))
            put("tags", tags.joinToString(","))
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db.writable().insertOrThrow("memories", null, values)
        return Memory(
            memoryId = id,
            scope = scope,
            content = content,
            createdAt = now,
            updatedAt = now,
            kind = kind,
            provenance = provenance,
            importance = importance.coerceIn(0f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
            tags = tags,
        )
    }

    override fun get(memoryId: Long): Memory? =
        db.readable().rawQuery("SELECT $COLUMNS FROM memories WHERE memory_id = ?", arrayOf(memoryId.toString()))
            .use { cursor -> if (cursor.moveToNext()) cursor.toMemory() else null }

    override fun update(memoryId: Long, content: String): Memory? {
        val existing = get(memoryId) ?: return null
        val now = db.now()
        val values = ContentValues().apply {
            put("content", content)
            put("content_norm", normalize(content))
            put("updated_at", now)
        }
        db.writable().update("memories", values, "memory_id = ?", arrayOf(memoryId.toString()))
        return existing.copy(content = content, updatedAt = now)
    }

    override fun delete(memoryId: Long) {
        db.writable().delete("memories", "memory_id = ?", arrayOf(memoryId.toString()))
    }

    override fun deleteScope(scope: MemoryScope) {
        val key = scope.key()
        if (key == null) {
            db.writable().delete("memories", "scope_kind = ? AND scope_key IS NULL", arrayOf(scope.kindName()))
        } else {
            db.writable().delete("memories", "scope_kind = ? AND scope_key = ?", arrayOf(scope.kindName(), key))
        }
    }

    override fun findDuplicate(scope: MemoryScope, content: String): Memory? {
        val key = scope.key()
        val scopeClause = if (key == null) "scope_key IS NULL" else "scope_key = ?"
        val args = buildList {
            add(scope.kindName())
            key?.let { add(it) }
            add(normalize(content))
        }
        return db.readable().rawQuery(
            "SELECT $COLUMNS FROM memories WHERE scope_kind = ? AND $scopeClause AND content_norm = ? LIMIT 1",
            args.toTypedArray(),
        ).use { cursor -> if (cursor.moveToNext()) cursor.toMemory() else null }
    }

    override fun list(scope: MemoryScope?, kind: MemoryKind?, limit: Int): List<Memory> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        scope?.let {
            // Two shapes rather than one clever clause: a keyed scope binds
            // its key, a keyless one (USER/GLOBAL) tests IS NULL and binds
            // nothing. Mixing them into a single expression is what makes
            // arg counts drift.
            val key = it.key()
            if (key == null) {
                conditions += "scope_kind = ? AND scope_key IS NULL"
                args += it.kindName()
            } else {
                conditions += "scope_kind = ? AND scope_key = ?"
                args += it.kindName()
                args += key
            }
        }
        kind?.let {
            conditions += "kind = ?"
            args += it.name
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return db.readable().rawQuery(
            "SELECT $COLUMNS FROM memories $where ORDER BY updated_at DESC LIMIT $limit",
            args.toTypedArray(),
        ).use { cursor ->
            val out = mutableListOf<Memory>()
            while (cursor.moveToNext()) cursor.toMemory()?.let { out += it }
            out
        }
    }

    override fun touch(memoryId: Long, at: Long) {
        runCatching {
            db.writable().execSQL("UPDATE memories SET last_accessed_at = ? WHERE memory_id = ?", arrayOf(at, memoryId))
        }
    }

    override fun relevant(
        sessionId: String,
        query: String,
        limit: Int,
        kinds: Set<MemoryKind>,
        projectIds: Set<String>,
    ): List<Memory> {
        if (query.isBlank() || kinds.isEmpty()) return emptyList()
        // Over-fetch, then rank in Kotlin: SQLite gives text relevance (FTS
        // rank) but knows nothing about importance/recency/provenance, and
        // encoding that weighting in SQL would make it unreadable and
        // untestable for no gain at these row counts.
        val candidates = if (db.ftsAvailable()) {
            candidatesFts(sessionId, query, limit * OVERFETCH, kinds, projectIds)
        } else {
            candidatesLike(sessionId, query, limit * OVERFETCH, kinds, projectIds)
        }
        return MemoryRanker.rank(candidates, query, now = db.now()).take(limit)
    }

    /**
     * SQL for "which scopes may this retrieval see", plus the bind args it
     * needs, in order.
     *
     * USER/GLOBAL are visible everywhere; SESSION only for the asking
     * session; PROJECT only when that project was explicitly named. TASK is
     * deliberately absent — task memory is working state, not something to
     * recall at large, so it is never returned by ordinary retrieval.
     *
     * [prefix] qualifies the column names for a joined query ("m." for the
     * FTS path, "" for the plain one) — building the string with the prefix
     * rather than rewriting it afterwards, because string-replacing column
     * names in finished SQL is how arg order silently drifts.
     */
    private fun visibilityClause(
        sessionId: String,
        projectIds: Set<String>,
        prefix: String = "",
    ): Pair<String, List<String>> {
        val clauses = mutableListOf(
            "$prefix" + "scope_kind = '$SCOPE_USER'",
            "$prefix" + "scope_kind = '$SCOPE_GLOBAL'",
            "($prefix" + "scope_kind = '$SCOPE_SESSION' AND $prefix" + "scope_key = ?)",
        )
        val args = mutableListOf(sessionId)

        if (projectIds.isNotEmpty()) {
            val placeholders = projectIds.joinToString(",") { "?" }
            clauses += "($prefix" + "scope_kind = '$SCOPE_PROJECT' AND $prefix" + "scope_key IN ($placeholders))"
            args += projectIds
        }
        return "(" + clauses.joinToString(" OR ") + ")" to args
    }

    private fun candidatesFts(
        sessionId: String,
        query: String,
        limit: Int,
        kinds: Set<MemoryKind>,
        projectIds: Set<String>,
    ): List<Memory> {
        val sanitized = TranscriptStore.sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return emptyList()
        val (visibility, visibilityArgs) = visibilityClause(sessionId, projectIds, prefix = "m.")
        val kindsIn = kinds.joinToString(",") { "'${it.name}'" }
        val args = listOf(sanitized) + visibilityArgs + limit.toString()
        return db.readable().rawQuery(
            """
            SELECT ${COLUMNS.split(", ").joinToString(", ") { "m.$it" }}
            FROM memories_fts f
            JOIN memories m ON m.memory_id = f.rowid
            WHERE f.memories_fts MATCH ?
              AND $visibility
              AND m.kind IN ($kindsIn)
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use { it.drain() }
    }

    /** See [TranscriptStore]'s `searchLike` — same fallback, same tradeoffs, used when FTS5 isn't available. */
    private fun candidatesLike(
        sessionId: String,
        query: String,
        limit: Int,
        kinds: Set<MemoryKind>,
        projectIds: Set<String>,
    ): List<Memory> {
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val (visibility, visibilityArgs) = visibilityClause(sessionId, projectIds)
        val kindsIn = kinds.joinToString(",") { "'${it.name}'" }
        val likeClause = tokens.joinToString(" OR ") { "content LIKE ?" }
        val args = visibilityArgs + tokens.map { "%${it.replace("%", "")}%" }
        return db.readable().rawQuery(
            """
            SELECT $COLUMNS FROM memories
            WHERE $visibility AND kind IN ($kindsIn) AND ($likeClause)
            ORDER BY memory_id DESC
            LIMIT $limit
            """.trimIndent(),
            args.toTypedArray(),
        ).use { it.drain() }
    }

    private fun Cursor.drain(): List<Memory> {
        val out = mutableListOf<Memory>()
        while (moveToNext()) toMemory()?.let { out += it }
        return out
    }

    /** Null for a row whose `scope_kind` this build doesn't recognise — skipped rather than guessed. */
    private fun Cursor.toMemory(): Memory? {
        val scope = decodeScope(
            getString(getColumnIndexOrThrow("scope_kind")),
            getString(getColumnIndexOrThrow("scope_key")),
        ) ?: return null
        val lastAccessedIndex = getColumnIndexOrThrow("last_accessed_at")
        return Memory(
            memoryId = getLong(getColumnIndexOrThrow("memory_id")),
            scope = scope,
            content = getString(getColumnIndexOrThrow("content")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            kind = runCatching { MemoryKind.valueOf(getString(getColumnIndexOrThrow("kind"))) }.getOrDefault(MemoryKind.DURABLE),
            provenance = runCatching { MemoryProvenance.valueOf(getString(getColumnIndexOrThrow("provenance"))) }
                .getOrDefault(MemoryProvenance.USER),
            importance = getFloat(getColumnIndexOrThrow("importance")),
            confidence = getFloat(getColumnIndexOrThrow("confidence")),
            tags = getString(getColumnIndexOrThrow("tags")).orEmpty().split(',').filter { it.isNotBlank() },
            lastAccessedAt = if (isNull(lastAccessedIndex)) null else getLong(lastAccessedIndex),
        )
    }

    companion object {
        const val SCOPE_SESSION = "SESSION"
        const val SCOPE_GLOBAL = "GLOBAL"
        const val SCOPE_USER = "USER"
        const val SCOPE_PROJECT = "PROJECT"
        const val SCOPE_TASK = "TASK"

        private const val OVERFETCH = 4

        private const val COLUMNS =
            "memory_id, scope_kind, scope_key, scope_session_id, content, created_at, updated_at, " +
                "kind, provenance, importance, confidence, tags, last_accessed_at"

        fun MemoryScope.kindName(): String = when (this) {
            is MemoryScope.Session -> SCOPE_SESSION
            MemoryScope.Global -> SCOPE_GLOBAL
            MemoryScope.User -> SCOPE_USER
            is MemoryScope.Project -> SCOPE_PROJECT
            is MemoryScope.Task -> SCOPE_TASK
        }

        fun MemoryScope.key(): String? = when (this) {
            is MemoryScope.Session -> sessionId
            is MemoryScope.Project -> projectId
            is MemoryScope.Task -> taskId
            MemoryScope.Global, MemoryScope.User -> null
        }

        fun decodeScope(kind: String?, key: String?): MemoryScope? = when (kind) {
            SCOPE_SESSION -> key?.let { MemoryScope.Session(it) }
            SCOPE_GLOBAL -> MemoryScope.Global
            SCOPE_USER -> MemoryScope.User
            SCOPE_PROJECT -> key?.let { MemoryScope.Project(it) }
            SCOPE_TASK -> key?.let { MemoryScope.Task(it) }
            else -> null
        }

        /** Dedup key: case/whitespace/trailing-punctuation insensitive, so "I prefer Kotlin." and "i prefer kotlin" are one memory. */
        fun normalize(content: String): String =
            content.lowercase().replace(Regex("[\\p{Punct}]+"), " ").replace(Regex("\\s+"), " ").trim()

        /**
         * Always attempts to create the FTS5 objects — best-effort, same
         * reasoning as [TranscriptStore.createFtsObjects]. Whether it
         * actually worked is decided at query time via
         * [SQLiteOpenHelperAccess.ftsAvailable].
         */
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE memories (
                    memory_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope_kind TEXT NOT NULL DEFAULT '$SCOPE_SESSION',
                    scope_key TEXT,
                    scope_session_id TEXT,
                    content TEXT NOT NULL,
                    content_norm TEXT,
                    kind TEXT NOT NULL DEFAULT '${MemoryKind.DURABLE}',
                    provenance TEXT NOT NULL DEFAULT '${MemoryProvenance.USER}',
                    importance REAL NOT NULL DEFAULT 0.5,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    tags TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_accessed_at INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_memories_scope ON memories(scope_kind, scope_key)")
            db.execSQL("CREATE INDEX idx_memories_dedup ON memories(scope_kind, scope_key, content_norm)")
            createFtsObjects(db)
        }

        /**
         * Adds everything schema v2 introduced to a v1 `memories` table.
         *
         * Existing rows are migrated, never dropped: a v1 memory had only a
         * nullable `scope_session_id`, so a null becomes [MemoryScope.Global]
         * and a non-null becomes [MemoryScope.Session] — exactly what v1's
         * own read path meant by those values. Provenance defaults to
         * [MemoryProvenance.USER] because v1 only ever wrote memories from
         * user conversation.
         */
        fun migrateToV2(db: SQLiteDatabase) {
            val existing = db.rawQuery("PRAGMA table_info(memories)", null).use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                names
            }
            if (existing.isEmpty()) return // no v1 table at all; onCreate will build v2

            fun addColumn(name: String, ddl: String) {
                if (name !in existing) runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN $ddl") }
            }

            addColumn("scope_kind", "scope_kind TEXT NOT NULL DEFAULT '$SCOPE_SESSION'")
            addColumn("scope_key", "scope_key TEXT")
            addColumn("content_norm", "content_norm TEXT")
            addColumn("kind", "kind TEXT NOT NULL DEFAULT '${MemoryKind.DURABLE}'")
            addColumn("provenance", "provenance TEXT NOT NULL DEFAULT '${MemoryProvenance.USER}'")
            addColumn("importance", "importance REAL NOT NULL DEFAULT 0.5")
            addColumn("confidence", "confidence REAL NOT NULL DEFAULT 1.0")
            addColumn("tags", "tags TEXT")
            addColumn("last_accessed_at", "last_accessed_at INTEGER")

            // Translate v1's nullable session column into the new scope pair.
            runCatching {
                db.execSQL(
                    "UPDATE memories SET scope_kind = '$SCOPE_GLOBAL', scope_key = NULL " +
                        "WHERE scope_session_id IS NULL",
                )
                db.execSQL(
                    "UPDATE memories SET scope_kind = '$SCOPE_SESSION', scope_key = scope_session_id " +
                        "WHERE scope_session_id IS NOT NULL",
                )
                db.execSQL("UPDATE memories SET content_norm = LOWER(TRIM(content)) WHERE content_norm IS NULL")
            }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope_kind, scope_key)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_dedup ON memories(scope_kind, scope_key, content_norm)") }
        }

        private fun createFtsObjects(db: SQLiteDatabase) {
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
