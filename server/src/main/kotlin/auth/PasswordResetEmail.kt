package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.users.UserService
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException

internal val PASSWORD_RESET_EMAIL_SUBJECT = LocalizedText(
    en = "Reset your Toadie password",
    pl = "Zresetuj hasło Toadie",
)
private val GREETING = LocalizedText(en = "Hi", pl = "Cześć")
private val INTRO = LocalizedText(
    en = "A password reset was requested for your Toadie account. Your password is unchanged. " +
        "If you did not request this, ignore this email. Use the single-use link below to choose a new password.",
    pl = "Ktoś poprosił o zresetowanie hasła do Twojego konta Toadie. Twoje hasło pozostaje bez zmian. " +
        "Jeśli to nie Ty prosiłeś/aś o reset, zignoruj tę wiadomość. Użyj jednorazowego linku poniżej, aby wybrać nowe hasło.",
)
private val EXPIRY = LocalizedText(en = "Link lifetime (minutes)", pl = "Ważność linku (minuty)")
private val COMPLETED_SUBJECT = LocalizedText(en = "Your Toadie password was reset", pl = "Twoje hasło Toadie zostało zresetowane")
private val COMPLETED_BODY = LocalizedText(
    en = "Your Toadie password was reset and your existing sessions were signed out. " +
        "If you did not do this, contact your administrator immediately.",
    pl = "Twoje hasło Toadie zostało zresetowane, a istniejące sesje zostały wylogowane. " +
        "Jeśli to nie Ty zresetowałeś/aś hasło, natychmiast skontaktuj się z administratorem.",
)

internal fun passwordResetEmailBody(name: String, token: String, appUrl: String, ttlSeconds: Long, language: String): String =
    buildString {
        appendLine("${GREETING.of(language)} $name,")
        appendLine()
        appendLine(INTRO.of(language))
        appendLine("${EXPIRY.of(language)}: ${(ttlSeconds + 59) / 60}")
        appendLine()
        // Fragments never reach the HTTP server or its access logs. GET never consumes a grant.
        appendLine("$appUrl/reset-password/confirm#token=$token")
    }

/** Uniform request latency: lookup and delivery run asynchronously; passwords are never touched. */
// Mail-send boundary: any provider failure is classified and audited without its text (security.md), cancellation is rethrown above.
@Suppress("TooGenericExceptionCaught")
internal suspend fun processPasswordReset(
    app: Application,
    users: UserService,
    resets: PasswordResetService,
    mailer: Mailer,
    appUrl: String,
    ttlSeconds: Long,
    email: String,
) {
    var token: String? = null
    try {
        val (userId, user) = users.findWithIdByEmail(email) ?: run {
            audit("password_reset.unknown_email", "email" to email)
            return
        }
        token = resets.issue(userId, user.authVersion) ?: return
        mailer.send(
            to = user.email,
            subject = PASSWORD_RESET_EMAIL_SUBJECT.of(user.language),
            body = passwordResetEmailBody(user.name, token, appUrl, ttlSeconds, user.language),
        )
        audit("password_reset.link_sent", "userId" to userId.toLong(), "email" to user.email)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        token?.let { resets.revoke(it) }
        // Mail-provider exceptions may include the message body: never log their text/cause.
        audit("password_reset.send_failed", "email" to email, "errorType" to e.javaClass.simpleName)
        app.log.error("Password reset delivery failed ({})", e.javaClass.simpleName)
    }
}

/** A notification failure cannot roll back a confirmed password change or turn its 204 into 500. */
// Mail-send boundary: any provider failure is classified and audited without its text (security.md), cancellation is rethrown above.
@Suppress("TooGenericExceptionCaught")
internal suspend fun notifyPasswordReset(app: Application, users: UserService, mailer: Mailer, userId: UInt) {
    try {
        val user = users.read(userId) ?: return
        mailer.send(user.email, COMPLETED_SUBJECT.of(user.language), COMPLETED_BODY.of(user.language))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        audit("password_reset.notification_failed", "userId" to userId.toLong(), "errorType" to e.javaClass.simpleName)
        app.log.error("Password reset notification failed ({})", e.javaClass.simpleName)
    }
}
