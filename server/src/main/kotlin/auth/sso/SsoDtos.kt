package ch.nokillswit.auth.sso

import kotlinx.serialization.Serializable

/** The sign-in button the SPA shows when SSO is on. */
@Serializable
data class SsoOption(val label: String)

/** `GET /api/v1/login/options`: what the sign-in page may offer besides the password form. `sso` is null with the flag off. */
@Serializable
data class LoginOptionsResponse(val sso: SsoOption?)

/** `POST /api/v1/login/sso/exchange`: the one-time code the callback put in the redirect fragment. */
@Serializable
data class SsoExchangeRequest(val code: String)
