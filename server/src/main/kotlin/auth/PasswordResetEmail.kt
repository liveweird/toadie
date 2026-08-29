package ch.nokillswit.auth

import ch.nokillswit.infra.mail.LocalizedText
import ch.nokillswit.infra.mail.passwordEmail

/**
 * Content of the password-reset email, rendered in the recipient's language. Thin wrapper
 * over the shared scaffold (infra/mail/PasswordEmail.kt) — only the intro copy, the password
 * label, and the subject live here. The route passes the recipient's stored language (V18).
 */
internal val PASSWORD_RESET_EMAIL_SUBJECT: LocalizedText = LocalizedText(
    en = "Your new Toadie password",
    pl = "Twoje nowe hasło Toadie",
)

private val INTRO = LocalizedText(
    en = "a password reset was requested for your Toadie account, so your previous " +
        "password no longer works. Sign in with the new password below and change it " +
        "afterwards. If you didn't request this, someone submitted your address on the " +
        "reset form — sign in and change the password now.",
    pl = "ktoś poprosił o zresetowanie hasła do Twojego konta Toadie, więc Twoje " +
        "poprzednie hasło już nie działa. Zaloguj się nowym hasłem poniżej, a potem je " +
        "zmień. Jeśli to nie Ty prosiłeś/aś o reset, ktoś podał Twój adres w formularzu — " +
        "zaloguj się i zmień hasło od razu.",
)

private val PASSWORD_LABEL = LocalizedText(en = "New password", pl = "Nowe hasło")

internal fun passwordResetEmailBody(name: String, password: String, appUrl: String?, language: String): String =
    passwordEmail(
        name = name,
        intro = INTRO,
        passwordLabel = PASSWORD_LABEL,
        password = password,
        appUrl = appUrl,
        language = language,
    )
