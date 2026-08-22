package ch.nokillswit.users

import kotlinx.serialization.Serializable

/**
 * The stored account role. Unlike a plain capability set, storage is a single column with a
 * CHECK constraint (V1): every account is exactly one of these, [USER] being the baseline.
 * On the wire (JWT `roles` claim, LoginResponse.roles) only ADDITIONAL roles travel — a
 * regular user serializes as the empty array — so adding a future role never changes the
 * shape for existing accounts.
 *
 * [ADMIN] — the management role: may reset any user's password (and future admin surfaces).
 * [USER] — the implicit baseline; never transmitted.
 */
@Serializable
enum class UserRole { ADMIN, USER }

@Serializable
data class User(
    val name: String,
    val email: String,
    val passwordHash: String,
    val role: UserRole = UserRole.USER,
    // Epoch millis of the last password change (0 = never). Server-internal; used to
    // invalidate refresh tokens minted before the change (see /api/v1/refresh).
    val passwordChangedAt: Long = 0,
) {
    /** The wire/claim shape: additional roles only — empty for a regular user. */
    val additionalRoles: Set<UserRole>
        get() = if (role == UserRole.ADMIN) setOf(UserRole.ADMIN) else emptySet()
}

@Serializable
data class PasswordUpdateRequest(
    val password: String,
    // Required when a caller changes their OWN password (even an admin); not required
    // for an admin resetting somebody else's.
    val currentPassword: String? = null,
)
