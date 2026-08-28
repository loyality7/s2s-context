package com.s2s.context.local

/**
 * Who the assistant is, as configuration.
 *
 * There is deliberately no default name in this file. The assistant's name
 * is something the user chooses — "Nova", "Friday", "my assistant", or
 * nothing at all — and a name compiled into the architecture is a name
 * every deployment is stuck with. [displayName] being nullable is the
 * honest encoding of "the user hasn't named it", which is different from
 * "the user named it the default".
 *
 * Distinct from [UserProfile]: this is who is answering, that is who is
 * asking. Distinct from memory: identity is chosen configuration, memory is
 * accumulated knowledge. Collapsing them would mean a stray remembered
 * sentence could rewrite the assistant's persona.
 */
data class AgentIdentity(
    /** Stable key for this identity's stored settings. Not shown to the user. */
    val agentId: String = "default",
    /** What the user calls it. Null means unnamed — the host should not invent one. */
    val displayName: String? = null,
    /**
     * Persona/behaviour instructions, contributed to the system prompt.
     *
     * This is the one place a persona belongs. Note it is data the host
     * stores, not a constant in the agent harness — which is what makes
     * "let the user edit how the assistant behaves" a settings change
     * rather than a release.
     */
    val instructions: String? = null,
    /** BCP-47 tag, e.g. "en-US". Null means "follow the device/host default". */
    val language: String? = null,
    /** Which TTS voice to speak with, as the speech layer's own voice id. Null means the host's default. */
    val voiceId: Int? = null,
    /** Free-form host-defined behaviour flags. Kept opaque here so adding one is not a schema change. */
    val preferences: Map<String, String> = emptyMap(),
) {
    /**
     * The identity's contribution to the system prompt, or null if it has
     * nothing to say — an unconfigured identity must add nothing rather
     * than a paragraph of empty scaffolding.
     */
    fun systemPromptFragment(): String? {
        val parts = buildList {
            displayName?.takeIf { it.isNotBlank() }?.let { add("You are $it.") }
            instructions?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            language?.takeIf { it.isNotBlank() }?.let { add("Reply in $it unless the user writes in another language.") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

/**
 * Stable, curated facts about the user.
 *
 * Deliberately small and explicit rather than "whatever we inferred". A
 * profile is the highest-trust, most persistent thing the assistant
 * believes about its user, so it is the last place that should accumulate
 * guesses: "I prefer concise answers" belongs here, "I'm currently
 * debugging the plugin loader" does not — that is a session or task fact.
 *
 * Stored as its own record rather than as [Memory] rows because a profile
 * is a small fixed set of curated fields the user can review in one place,
 * whereas memory is an open-ended, growing collection. Both persist; only
 * one is meant to be read in full.
 */
data class UserProfile(
    /** What the user wants to be called. Null when they haven't said. */
    val displayName: String? = null,
    /** e.g. "concise", "detailed". Free-form: the host decides what it honours. */
    val responseStyle: String? = null,
    val language: String? = null,
    /**
     * Additional curated key/value preferences. Written only through
     * deliberate action (a settings screen, or an explicit user
     * instruction) — never as a side effect of conversation.
     */
    val preferences: Map<String, String> = emptyMap(),
) {
    fun systemPromptFragment(): String? {
        val parts = buildList {
            displayName?.takeIf { it.isNotBlank() }?.let { add("The user's name is $it.") }
            responseStyle?.takeIf { it.isNotBlank() }?.let { add("They prefer $it responses.") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}

/**
 * Persistence for [AgentIdentity] and [UserProfile].
 *
 * A contract rather than a concrete class so identity/profile can later
 * live wherever the host's memory provider lives (local SQLite today, an
 * encrypted store or a remote provider later) without the agent layer
 * knowing. Mentions no storage technology for the same reason
 * [MemoryRepository] does not.
 */
interface IdentityStore {
    fun loadIdentity(agentId: String = "default"): AgentIdentity?
    fun saveIdentity(identity: AgentIdentity)

    fun loadProfile(): UserProfile?
    fun saveProfile(profile: UserProfile)
}
