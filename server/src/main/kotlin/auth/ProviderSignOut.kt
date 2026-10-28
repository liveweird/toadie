package ch.nokillswit.auth

import io.ktor.util.AttributeKey

/**
 * The seam between `POST /logout` and an OIDC provider's end-session endpoint (RP-initiated logout), published by
 * `auth/sso/SsoRoutes.kt` only when `sso.enabled=true`; `auth/` never imports `auth.sso`. Called AFTER the session row was
 * deleted, with the ID token that row held: nothing here can undo a revocation.
 */
fun interface ProviderSignOut {
    suspend fun endSessionUrl(idToken: String): ProviderSignOutResult
}

/** The verdict for one logout; [auditWire] is the `sso` field of the `logout` audit event. */
sealed interface ProviderSignOutResult {
    val auditWire: String

    /** The provider ended-session location the browser must navigate to. Never logged or audited. */
    class Redirect(val url: String) : ProviderSignOutResult {
        override val auditWire = "redirected"

        // The URL embeds the ID token: a stray log line must never print it.
        override fun toString(): String = "Redirect"
    }

    /** The provider advertises no usable end-session endpoint (or SSO is no longer configured): local sign-out only. */
    data object Unsupported : ProviderSignOutResult {
        override val auditWire = "unsupported"
    }

    /** Discovery could not be read right now: local sign-out only. */
    data object Unavailable : ProviderSignOutResult {
        override val auditWire = "unavailable"
    }
}

val ProviderSignOutKey = AttributeKey<ProviderSignOut>("ProviderSignOut")
