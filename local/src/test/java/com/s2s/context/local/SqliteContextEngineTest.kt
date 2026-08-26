package com.s2s.context.local

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Neither of Robolectric's SQLite shadows (LEGACY's sqlite4java, or NATIVE's
 * bundled Android SQLite build) compiles in the FTS5 module — real Android
 * devices (API 11+) do. [TranscriptStore]/[SqliteMemoryRepository] detect
 * this at open time and fall back to a `LIKE`-based scan, so these tests run
 * against that fallback path rather than against FTS5 itself. FTS5's actual
 * ranking/matching behavior needs an on-device or emulator instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
class SqliteContextEngineTest {

    private fun engine(sessionId: String = "session-1", systemPrompt: String = "system") =
        SqliteContextEngine(ApplicationProvider.getApplicationContext(), sessionId, systemPrompt)

    @Test
    fun `basic message flow`() {
        val ctx = engine()
        ctx.addUser("hello")
        ctx.addAssistant("hi there")

        val messages = ctx.messages()
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        assertEquals("hello", messages[1].content)
        assertEquals("assistant", messages[2].role)
        assertEquals("hi there", messages[2].content)
    }

    @Test
    fun `replaceLastUser overwrites the most recent user turn`() {
        val ctx = engine()
        ctx.addUser("I am thinking...")
        ctx.replaceLastUser("I am thinking about the weather.")

        val messages = ctx.messages()
        assertEquals(2, messages.size)
        assertEquals("I am thinking about the weather.", messages[1].content)
    }

    @Test
    fun `tool result is formatted and attributed to user role`() {
        val ctx = engine()
        ctx.addToolResult("get_weather", "Sunny 25C")

        val messages = ctx.messages()
        assertEquals("user", messages[1].role)
        assertEquals("[tool get_weather returned] Sunny 25C", messages[1].content)
    }

    @Test
    fun `dropLastUserIfUnanswered removes an interrupted question`() {
        val ctx = engine()
        ctx.addUser("first question")
        ctx.addAssistant("first answer")
        ctx.addUser("interrupted question")

        ctx.dropLastUserIfUnanswered()

        val messages = ctx.messages()
        assertEquals("assistant", messages.last().role)
        assertEquals("first answer", messages.last().content)
    }

    // --- Full transcript ---

    @Test
    fun `full transcript survives working-context clear`() {
        val ctx = engine(sessionId = "s")
        ctx.addUser("message 1")
        ctx.addAssistant("reply 1")
        ctx.clear()

        // clear() only resets working-context cursor state, not the transcript.
        val store = TranscriptStore(ApplicationProvider.getApplicationContext())
        val full = store.fullHistory("s")
        assertEquals(2, full.size)
    }

    @Test
    fun `old events remain persisted after the recent window would have dropped them`() {
        val config = WorkingContextConfig(recentEventLimit = 4)
        val store = TranscriptStore(ApplicationProvider.getApplicationContext())
        val ctx = SqliteContextEngine(ApplicationProvider.getApplicationContext(), "s2", "system", config)

        repeat(20) { i ->
            ctx.addUser("user message $i")
            ctx.addAssistant("assistant reply $i")
        }

        // 40 events total, but only recentEventLimit appear verbatim in messages().
        val full = store.fullHistory("s2")
        assertEquals(40, full.size)
        assertTrue(ctx.messages().size - 1 <= config.recentEventLimit) // -1 for the system message
    }

    // --- Retrieval ---

    @Test
    fun `relevant older history is retrievable by keyword even after leaving the recent window`() {
        val config = WorkingContextConfig(recentEventLimit = 2)
        val ctx = SqliteContextEngine(ApplicationProvider.getApplicationContext(), "s3", "system", config)

        ctx.addUser("I want to talk about kayaking trips")
        ctx.addAssistant("Sure, kayaking is fun")
        // Push the kayaking turn out of the recent window with unrelated chatter.
        repeat(5) { i ->
            ctx.addUser("unrelated message $i")
            ctx.addAssistant("unrelated reply $i")
        }
        ctx.addUser("what did I say about kayaking?")

        val messages = ctx.messages()
        val relevantLine = messages.firstOrNull { it.content.contains("kayaking", ignoreCase = true) && it.role == "system" }
        assertTrue("expected a system message surfacing the kayaking mention", relevantLine != null)
    }

    @Test
    fun `irrelevant old history is not injected into the working context`() {
        val config = WorkingContextConfig(recentEventLimit = 2)
        val ctx = SqliteContextEngine(ApplicationProvider.getApplicationContext(), "s4", "system", config)

        ctx.addUser("tell me about volcanoes")
        ctx.addAssistant("volcanoes are geologically active")
        repeat(5) { i ->
            ctx.addUser("filler $i")
            ctx.addAssistant("filler reply $i")
        }
        ctx.addUser("what's the capital of France?")

        val messages = ctx.messages()
        assertFalse(messages.any { it.content.contains("volcano", ignoreCase = true) })
    }

    // --- Long conversation ---

    @Test
    fun `a very long conversation does not require sending the full transcript`() {
        val config = WorkingContextConfig(recentEventLimit = 10)
        val ctx = SqliteContextEngine(ApplicationProvider.getApplicationContext(), "s5", "system", config)

        repeat(500) { i ->
            ctx.addUser("user turn $i")
            ctx.addAssistant("assistant turn $i")
        }

        val messages = ctx.messages()
        // 1000 events total, but the prompt stays bounded regardless.
        assertTrue(messages.size < 20)
    }

    // --- Memory ---

    @Test
    fun `durable memory survives session recreation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = SqliteContextEngine(context, "s6", "system")
        first.memories.create(MemoryScope.Session("s6"), "User prefers metric units")

        val second = SqliteContextEngine(context, "s6", "system")
        val found = second.memories.relevant("s6", "units", limit = 5)
        assertTrue(found.any { it.content.contains("metric") })
    }

    @Test
    fun `memory is not injected into every prompt, only when relevant`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = SqliteContextEngine(context, "s7", "system")
        ctx.memories.create(MemoryScope.Session("s7"), "User's favorite color is teal")

        ctx.addUser("what's the weather today?")
        val unrelated = ctx.messages()
        assertFalse(unrelated.any { it.content.contains("teal") })

        ctx.addUser("what's my favorite color?")
        val related = ctx.messages()
        assertTrue(related.any { it.content.contains("teal") })
    }

    @Test
    fun `global memory is visible across sessions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = SqliteContextEngine(context, "session-A", "system")
        a.memories.create(MemoryScope.Global, "The project uses Kotlin and Gradle")

        val b = SqliteContextEngine(context, "session-B", "system")
        val found = b.memories.relevant("session-B", "Kotlin", limit = 5)
        assertTrue(found.any { it.content.contains("Kotlin") })
    }

    // --- Session isolation ---

    @Test
    fun `sessions do not share transcript state`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = SqliteContextEngine(context, "session-A2", "system")
        val b = SqliteContextEngine(context, "session-B2", "system")

        a.addUser("only in session A")
        b.addUser("only in session B")

        assertTrue(a.messages().any { it.content == "only in session A" })
        assertFalse(a.messages().any { it.content == "only in session B" })
        assertTrue(b.messages().any { it.content == "only in session B" })
        assertFalse(b.messages().any { it.content == "only in session A" })
    }

    @Test
    fun `session-scoped memory does not leak into another session`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = SqliteContextEngine(context, "session-A3", "system")
        val b = SqliteContextEngine(context, "session-B3", "system")

        a.memories.create(MemoryScope.Session("session-A3"), "A private fact about session A")

        val foundInB = b.memories.relevant("session-B3", "private fact", limit = 5)
        assertTrue(foundInB.isEmpty())
    }

    // --- Restart / persistence ---

    @Test
    fun `persisted transcript is restored after creating a new engine instance`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = SqliteContextEngine(context, "s8", "original system prompt")
        first.addUser("remember this")
        first.addAssistant("I will")
        val saved = first.toJson()

        val second = SqliteContextEngine(context, "s8", "different default prompt")
        second.fromJson(saved)

        val messages = second.messages()
        assertEquals("original system prompt", messages[0].content)
        assertTrue(messages.any { it.content == "remember this" })
    }

    @Test
    fun `fromJson refuses state saved under a different session`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = SqliteContextEngine(context, "session-X", "system")
        val saved = a.toJson()

        val b = SqliteContextEngine(context, "session-Y", "system")
        try {
            b.fromJson(saved)
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    // --- Context assembly ---

    @Test
    fun `assembled context contains recent history, relevant memory, and current request`() {
        val config = WorkingContextConfig(recentEventLimit = 2)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = SqliteContextEngine(context, "s9", "you are helpful", config)
        ctx.memories.create(MemoryScope.Session("s9"), "User likes concise answers")

        ctx.addUser("keep it short please")
        ctx.addAssistant("ok")
        ctx.addUser("tell me about answers being short")

        val messages = ctx.messages()
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("you are helpful"))
        assertTrue(messages.any { it.role == "system" && it.content.contains("concise") })
        assertEquals("tell me about answers being short", messages.last().content)
    }

    // --- Compression / bounded working context ---

    @Test
    fun `working context stays within configured recent-event bound regardless of transcript size`() {
        val config = WorkingContextConfig(recentEventLimit = 6)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = SqliteContextEngine(context, "s10", "system", config)

        repeat(50) { i ->
            ctx.addUser("u$i")
            ctx.addAssistant("a$i")
        }

        val verbatimTurns = ctx.messages().drop(1).filter { it.role == "user" || it.role == "assistant" }
        assertTrue(verbatimTurns.size <= config.recentEventLimit)
    }

    @Test
    fun `compaction never strands a leading assistant message`() {
        val config = WorkingContextConfig(recentEventLimit = 4)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = SqliteContextEngine(context, "s11", "system", config)

        repeat(10) { i ->
            ctx.addUser("u$i")
            ctx.addAssistant("a$i")
            val turns = ctx.messages().filterNot { it.role == "system" }
            assertNotEquals("assistant", turns.firstOrNull()?.role ?: "user")
        }
    }
}
