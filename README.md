# s2s-context

The context/memory plugin for [speech-to-speech-mobile](https://github.com/loyality7/speech-to-speech-mobile).
Implements the published `ContextEngine` contract with a design that never
solves "the conversation got too long" by deleting history.

```
speech-to-speech-mobile
        │
        │ ContextEngine contract
        ▼
     s2s-context
        │
        ├── common   — plugin identity interface, nothing storage-specific
        └── local    — SqliteContextEngine: full transcript + retrieval + memory + working context
```

## The problem this solves

The old `ChatHistory` (removed from core in this extraction) kept a bounded
in-memory deque and threw away anything past the window, folding it into one
running summary string. That summary is lossy and gets worse the longer a
conversation runs — by turn 500 it's a paragraph of mush.

`SqliteContextEngine` never deletes anything from the transcript. Instead:

- **Full transcript** — every turn ever recorded, in SQLite, forever (until
  `wipeSessionCompletely()` is called explicitly).
- **Working context** — what a turn's prompt actually contains: the most
  recent N turns verbatim, plus whatever old turns or durable memories are
  *relevant* to what the user just said.
- **Retrieval** — FTS5 keyword search over the transcript, so "what did we
  discuss about kayaking" finds the answer even if it happened 400 turns ago
  and long since scrolled out of the recent window.
- **Long-term memory** — durable facts ("the user prefers metric units"),
  separate from the transcript, session-scoped or global, retrieved the same
  relevance-based way — never injected into every single prompt.

A month-long conversation never sends a month of messages to the model. It
sends the last dozen turns plus whatever from the whole history turns out to
matter for the current question.

## Architecture inside `local`

```
                    ContextEngine (published core contract)
                            │
                    SqliteContextEngine
                            │
       ┌────────────────────┼────────────────────┐
       │                    │                    │
 TranscriptStore        Retrieval           MemoryRepository
 (SQLite, append-only)  (FTS5 keyword       (SQLite, separate
                         search over the     table, CRUD +
                         transcript)         relevance retrieval)
       │                    │                    │
       └────────────────────┼────────────────────┘
                            ▼
                    WorkingContextBuilder
                    (assembles the bounded
                     prompt every turn sees)
```

`TranscriptStore` and `MemoryRepository` are separate concerns sharing one
SQLite file — a fact recorded is not the same thing as a turn that was said,
and they have independent lifecycles (memory can outlive the conversation
that produced it; the transcript is per-session).

FTS5 has shipped in Android's SQLite since API 11, but a few non-standard
SQLite builds (Robolectric's JVM test shadow, notably) don't compile it in —
`TranscriptStore`/`SqliteMemoryRepository` detect this once at first real use
and fall back to a `LIKE`-based scan rather than crashing. Real devices use
FTS5; the fallback exists for environments that can't.

## Installing

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

dependencies {
    implementation("com.github.loyality7.s2s-context:local:0.1.0")
}
```

## Host composition

```kotlin
val sessionId = UUID.randomUUID().toString()
val history = SqliteContextEngine(
    context = androidContext,
    sessionId = sessionId,
    systemPrompt = "You are a helpful assistant.",
)

val engine = S2SEngine(
    context = androidContext,
    config = config,
    languageModel = languageModel,
    history = history,
    sessionId = sessionId,
)
```

`sessionId` is passed to both `SqliteContextEngine` and `S2SEngine` — they
must agree, since `SqliteContextEngine` scopes its transcript/memory queries
to the session it was constructed for, and `S2SEngine` uses the same ID for
`ToolContext`. See `SqliteContextEngine.fromJson`'s doc for what happens if
they don't.

### Durable memory (beyond the `ContextEngine` contract)

```kotlin
history.memories.create(MemoryScope.Session(sessionId), "User prefers concise answers")
history.memories.create(MemoryScope.Global, "This deployment runs on a Pixel 8, no GPU delegate")
```

`MemoryRepository` isn't part of core's `ContextEngine` contract — core has
no opinion on memory management, only on what a turn's prompt looks like.
`SqliteContextEngine.memories` exposes it as an extra, plugin-specific API
the host can use directly.

## What's NOT here

- No embeddings, no vector index — FTS5 keyword search only. A future
  `s2s-context-vector` implementation can add semantic retrieval behind the
  same `Retrieval` interface without this repo or core changing.
- No remote memory service — everything is local SQLite. A future
  `s2s-context-remote` can implement `ContextEngine` against a hosted memory
  API the same way this repo implements it against SQLite.
- No agentic memory curation, no ranking model, no knowledge graph. `relevant()`
  is a keyword match, not a reasoning step about what's worth remembering.

## Testing strategy

`SqliteContextEngineTest` runs on the JVM via Robolectric (real Android
`SQLiteOpenHelper`/`SQLiteDatabase` behavior, simulated) — no emulator, no
device. It exercises the full transcript, retrieval, memory, session
isolation, restart/persistence, and bounded-working-context behavior the
architecture is built around, all against the `LIKE`-fallback path since
Robolectric's SQLite build has no FTS5 module. FTS5's actual ranking quality
needs an on-device or emulator instrumented test — not covered here.

## Publishing

Same JitPack mechanism as `speech-to-speech-mobile` and `s2s-llm` — push, tag,
JitPack builds on first resolution. Module coordinates follow JitPack's
multi-module convention (`com.github.loyality7.s2s-context:<module-name>`),
not a custom override — see `s2s-llm`'s `build.gradle.kts` comments for why
a custom `artifactId` breaks resolution.
