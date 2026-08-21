package com.example.paymentsmstracker.splitwise

data class SplitwiseUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val email: String = ""
) {
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { email }
}

data class SplitwiseGroup(
    val id: Long,
    val name: String,
    val members: List<SplitwiseUser>
)

enum class SplitwiseAuthMethod {
    NONE,
    /** In-app web login / direct signup session cookies. */
    SESSION,
    /** OAuth authorize + access token. */
    OAUTH,
    /** Personal API key (Bearer). */
    TOKEN
}

data class SplitwiseAuthState(
    val consumerKey: String = "",
    val consumerSecret: String = "",
    /**
     * Personal API key or OAuth access token.
     * Both are sent as `Authorization: Bearer …`.
     */
    val accessToken: String = "",
    val currentUserId: Long = 0L,
    val currentUserName: String = "",
    val groups: List<SplitwiseGroup> = emptyList(),
    val defaultGroupId: Long = 0L,
    /** Web session cookie `_splitwise_session` captured after in-app sign-in. */
    val sessionCookie: String = "",
    /** Web session cookie `user_credentials` captured after in-app sign-in. */
    val userCredentialsCookie: String = "",
    /** Anti-CSRF token sent as `X-CSRF-Token` on mutating requests. */
    val csrfToken: String = "",
    /** Which connect path last succeeded (any one is enough). */
    val authMethod: SplitwiseAuthMethod = SplitwiseAuthMethod.NONE
) {
    /** OAuth app credentials present (needed only for browser sign-in). */
    val isOAuthConfigured: Boolean
        get() = consumerKey.isNotBlank() && consumerSecret.isNotBlank()

    @Deprecated("Use isOAuthConfigured", ReplaceWith("isOAuthConfigured"))
    val isConfigured: Boolean
        get() = isOAuthConfigured

    val isSignedIn: Boolean
        get() = accessToken.isNotBlank() || sessionCookie.isNotBlank()

    /** Prefer stored method; fall back to inferring from credentials. */
    val resolvedAuthMethod: SplitwiseAuthMethod
        get() = when {
            authMethod != SplitwiseAuthMethod.NONE -> authMethod
            sessionCookie.isNotBlank() -> SplitwiseAuthMethod.SESSION
            accessToken.isNotBlank() && isOAuthConfigured -> SplitwiseAuthMethod.OAUTH
            accessToken.isNotBlank() -> SplitwiseAuthMethod.TOKEN
            else -> SplitwiseAuthMethod.NONE
        }

    fun groupById(id: Long): SplitwiseGroup? = groups.firstOrNull { it.id == id }

    fun resolveUserId(participant: String): Long? {
        if (participant.equals("ME", ignoreCase = true)) {
            return currentUserId.takeIf { it > 0L }
        }
        return null
    }
}
