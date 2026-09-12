package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.infra.mail.respondMailUnavailable
import ch.nokillswit.users.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Content of the email-MFA sign-in-code email, rendered in the recipient's language.
 * A sign-in code needs no link — the user is already mid-login. The route passes the
 * recipient's stored language (V18), through the shared LocalizedText content primitive.
 */
internal val MFA_EMAIL_SUBJECT: LocalizedText = LocalizedText(
    en = "Toadie: your sign-in code",
    pl = "Toadie: twój kod logowania",
)

private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")

private val CODE_LABEL = LocalizedText(en = "Your sign-in code", pl = "Twój kod logowania")

internal fun mfaEmailBody(name: String, code: String, ttlMinutes: Long, language: String): String = buildString {
    val intro = LocalizedText(
        en = "someone — hopefully you — is signing in to your Toadie account. Enter the code " +
            "below to finish signing in; it is valid for about $ttlMinutes minutes. If this " +
            "wasn't you, your password may be compromised — change it after signing in.",
        pl = "ktoś — mamy nadzieję, że Ty — loguje się na Twoje konto Toadie. Wpisz poniższy " +
            "kod, aby dokończyć logowanie; kod jest ważny około $ttlMinutes min. Jeśli to " +
            "nie Ty, Twoje hasło mogło wpaść w niepowołane ręce — zmień je po zalogowaniu.",
    )
    appendLine("${GREETING.of(language)} $name,")
    appendLine()
    appendLine(intro.of(language))
    appendLine()
    appendLine("${CODE_LABEL.of(language)}:")
    appendLine()
    appendLine(code)
}

/**
 * The MFA half of the login handler — the issuance WORKER, extracted from the route
 * registrar (route files stay declarative; the detekt exemption covers registration, not
 * workers): correct credentials answered with a challenge instead of tokens. Responds
 * itself (503 fail-closed on a mail-less deployment, else the challenge); the login
 * handler returns right after calling it.
 */
// Mail-send boundary: any provider failure is classified and audited without its text (security.md), cancellation is rethrown above.
@Suppress("TooGenericExceptionCaught")
internal suspend fun issueMfaChallenge(
    call: ApplicationCall,
    challenges: MfaChallenges,
    challengeMailer: Mailer?,
    codeTtlMinutes: Long,
    userId: UInt,
    user: User,
) {
    if (challengeMailer == null) {
        // Fail closed (the password-reset 503 precedent): silently skipping the
        // second factor would downgrade security on a config change. The
        // features PUT still works, so an admin can always flip the flag back.
        audit("login.mfa_unavailable", "email" to user.email, "userId" to userId.toLong())
        call.respondMailUnavailable("multi-factor login")
        return
    }
    val challenge = challenges.issue(userId, user.authVersion)
    audit("login.mfa_challenge", "email" to user.email, "userId" to userId.toLong())
    // Challenge stored BEFORE responding (the user submits the code right away);
    // only the delivery is fire-and-forget, like the password-reset email.
    val app = call.application
    app.launch {
        try {
            challengeMailer.send(
                to = user.email,
                subject = MFA_EMAIL_SUBJECT.of(user.language),
                body = mfaEmailBody(user.name, challenge.code, codeTtlMinutes, user.language),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Mail-provider exceptions may include the message body: never log their text/cause
            // (the email body itself carries the 6-digit sign-in code).
            audit("login.mfa_send_failed", "email" to user.email, "errorType" to e.javaClass.simpleName)
            app.log.error("MFA code email delivery failed for {} ({})", user.email, e.javaClass.simpleName)
        }
    }
    call.respond(
        MfaChallengeResponse(
            challengeId = challenge.challengeId,
            expiresAt = challenge.expiresAt,
        ),
    )
}
