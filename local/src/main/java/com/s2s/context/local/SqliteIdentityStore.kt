package com.s2s.context.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/**
 * [IdentityStore] on the same SQLite file everything else in this module
 * uses — one database, more tables, per the rule that a second database is
 * a migration and backup problem nobody asked for.
 *
 * Two single-row-per-key tables rather than columns on some larger entity:
 * identity and profile are small, read on nearly every turn, and edited by
 * a settings screen. A key/value shape means adding a preference is a map
 * entry rather than a schema migration.
 */
internal class SqliteIdentityStore(private val db: SQLiteOpenHelperAccess) : IdentityStore {

    override fun loadIdentity(agentId: String): AgentIdentity? =
        db.readable().rawQuery(
            "SELECT agent_id, display_name, instructions, language, voice_id, preferences FROM agent_identity WHERE agent_id = ?",
            arrayOf(agentId),
        ).use { cursor ->
            if (!cursor.moveToNext()) return null
            val voiceIndex = cursor.getColumnIndexOrThrow("voice_id")
            AgentIdentity(
                agentId = cursor.getString(cursor.getColumnIndexOrThrow("agent_id")),
                displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                instructions = cursor.getString(cursor.getColumnIndexOrThrow("instructions")),
                language = cursor.getString(cursor.getColumnIndexOrThrow("language")),
                voiceId = if (cursor.isNull(voiceIndex)) null else cursor.getInt(voiceIndex),
                preferences = decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("preferences"))),
            )
        }

    override fun saveIdentity(identity: AgentIdentity) {
        val values = ContentValues().apply {
            put("agent_id", identity.agentId)
            put("display_name", identity.displayName)
            put("instructions", identity.instructions)
            put("language", identity.language)
            put("voice_id", identity.voiceId)
            put("preferences", encodeMap(identity.preferences))
        }
        // REPLACE rather than update-then-insert: agent_id is the primary
        // key, so this is one statement and cannot race itself into two rows.
        db.writable().insertWithOnConflict("agent_identity", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun loadProfile(): UserProfile? =
        db.readable().rawQuery(
            "SELECT display_name, response_style, language, preferences FROM user_profile WHERE profile_id = ?",
            arrayOf(PROFILE_ID),
        ).use { cursor ->
            if (!cursor.moveToNext()) return null
            UserProfile(
                displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                responseStyle = cursor.getString(cursor.getColumnIndexOrThrow("response_style")),
                language = cursor.getString(cursor.getColumnIndexOrThrow("language")),
                preferences = decodeMap(cursor.getString(cursor.getColumnIndexOrThrow("preferences"))),
            )
        }

    override fun saveProfile(profile: UserProfile) {
        val values = ContentValues().apply {
            put("profile_id", PROFILE_ID)
            put("display_name", profile.displayName)
            put("response_style", profile.responseStyle)
            put("language", profile.language)
            put("preferences", encodeMap(profile.preferences))
        }
        db.writable().insertWithOnConflict("user_profile", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** JSON, not a delimited string: a preference value can contain anything, including whatever separator would have been chosen. */
    private fun encodeMap(map: Map<String, String>): String =
        if (map.isEmpty()) "" else JSONObject(map as Map<*, *>).toString()

    private fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    companion object {
        /** Exactly one profile row: there is one user of a personal assistant on one device. */
        private const val PROFILE_ID = "self"

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS agent_identity (
                    agent_id TEXT PRIMARY KEY,
                    display_name TEXT,
                    instructions TEXT,
                    language TEXT,
                    voice_id INTEGER,
                    preferences TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_profile (
                    profile_id TEXT PRIMARY KEY,
                    display_name TEXT,
                    response_style TEXT,
                    language TEXT,
                    preferences TEXT
                )
                """.trimIndent(),
            )
        }
    }
}
