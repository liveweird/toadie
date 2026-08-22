package ch.nokillswit.infra.db

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*

/** The V3 seed account's well-known bcrypt hash (plaintext "changeme"). */
internal const val SEED_PASSWORD_HASH = "\$2y\$12\$VD60LjzPo00G5MtaWE3h9OrqYUid.MVxc5D7oHsM8oErnD9wuIvya"

internal const val SEED_ADMIN_EMAIL = "admin@toadie.local"

/**
 * Post-migration bootstrap that neutralizes the template seed credentials outside development.
 * Runs after [configureDatabase] (needs [UserServiceKey]); registered in application.yaml.
 *
 * - `ADMIN_INITIAL_PASSWORD` (config `bootstrap.adminInitialPassword`), when set, rotates the V3
 *   bootstrap admin's password — but only while it still carries the well-known seed hash, so a
 *   password the admin chose later is never overwritten.
 * - Outside development mode startup **fails closed** (mirroring the JWT-secret check in
 *   plugins/Security.kt) if any active account still carries the well-known seed hash — a
 *   deployment cannot boot with the `changeme` backdoor present.
 */
suspend fun Application.configureBootstrap() {
    val userService = attributes[UserServiceKey]

    val adminInitialPassword = environment.config
        .propertyOrNull("bootstrap.adminInitialPassword")?.getString()?.takeIf { it.isNotBlank() }
    if (adminInitialPassword != null) {
        val rotated = userService.rotatePasswordIfHashMatches(
            email = SEED_ADMIN_EMAIL,
            expectedHash = SEED_PASSWORD_HASH,
            newHash = hashPassword(adminInitialPassword),
        )
        if (rotated > 0) log.info("Bootstrap: rotated the seed admin password from ADMIN_INITIAL_PASSWORD")
    }

    if (!developmentMode) {
        val remaining = userService.countActiveWithPasswordHash(SEED_PASSWORD_HASH)
        if (remaining > 0) {
            error(
                "$remaining active account(s) still use the well-known seed password 'changeme' — " +
                    "set ADMIN_INITIAL_PASSWORD (or rotate them manually) before starting outside development."
            )
        }
    }
}
