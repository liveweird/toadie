package ch.nokillswit.users

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val UserServiceKey = AttributeKey<UserService>("UserService")

data class UserListFilter(
    val name: String? = null,
    val email: String? = null,
    val role: UserRole? = null,
    /**
     * Feature-flag state filter — the pair travels together (the route 400s a lone half):
     * [feature] names the flag, [featureEnabled] the state to match.
     */
    val feature: Feature? = null,
    val featureEnabled: Boolean? = null,
)

data class UserListResult(
    val items: List<UserResponse>,
    val total: Long,
)

// Deliberately no "role": the wire shape is a set, and a set has no order (the Lettuce rule).
private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to UserService.Users.id,
    "name" to UserService.Users.name,
    "email" to UserService.Users.email,
)

/** The ONE sortable whitelist — the route's `parsePaging` argument derives from the column map
 *  above, so the two can never drift apart (a mismatch used to be a runtime 500). */
val USER_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class UserService(private val database: R2dbcDatabase) {
    object Users : UIntIdTable() {
        val name = varchar("name", length = 50)
        // Uniqueness is enforced by a partial unique index (active rows only) in migration V1,
        // so a soft-deleted user frees its email. Exposed table defs are query-only (not DDL),
        // so this column carries no `.uniqueIndex()`.
        val email = varchar("email", length = 254)
        val passwordHash = varchar("password_hash", length = 255)
        // Single-column role storage (CHECK in V1); UserRole.USER is the baseline.
        val role = varchar("role", length = 20).default(UserRole.USER.name)
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        val passwordChangedAt = long("password_changed_at").default(0)
        val authVersion = long("auth_version").default(0)
        // Per-user language (V18): sign-in UI language + the language of every email sent
        // to the user. No CHECK — SUPPORTED_LANGUAGES is the whitelist.
        val language = varchar("language", length = 10).default("en")
        // Service accounts (V41, 2.15.0): a row that can never authenticate. A CHECK
        // constraint (V41) forbids service_account=true with role=ADMIN.
        val serviceAccount = bool("service_account").default(false)
    }

    // Per-user feature flags (V12) — the DISABLED set; no row = enabled.
    object UserDisabledFeatures : Table("user_disabled_features") {
        val userId = reference("user_id", Users)
        val feature = varchar("feature", length = 30)
        override val primaryKey = PrimaryKey(userId, feature)
    }

    suspend fun create(user: User): UInt = suspendTransaction(database) {
        // Re-checked service-side so direct callers stay guarded (the feature-template rule);
        // the plaintext-password rules can't apply here — only the bcrypt hash arrives.
        validateNameAndEmail(user.name, user.email)
        validateLanguage(user.language)
        val newRecord = Users.insert {
            it[name] = user.name
            it[email] = canonicalEmail(user.email)
            it[passwordHash] = user.passwordHash
            it[role] = user.role.name
            it[language] = user.language
        }
        val id = newRecord[Users.id].value
        // MFA is the one inverted-default flag (opt-in): every new user starts with the
        // disabled row present, mirroring the V13 seed for pre-existing users. All creation
        // paths (admin create, test seeds) funnel through here.
        UserDisabledFeatures.insert {
            it[UserDisabledFeatures.userId] = id
            it[UserDisabledFeatures.feature] = Feature.MFA.name
        }
        id
    }

    suspend fun read(id: UInt): User? = suspendTransaction(database) {
        Users.selectAll()
            .where { (Users.id eq id) and human() }
            .toList()
            .singleOrNull()
            ?.let { it.toUser(featuresOf(it[Users.id].value)) }
    }

    suspend fun findWithIdByEmail(email: String): Pair<UInt, User>? = suspendTransaction(database) {
        // Stored emails are canonical; folding the argument too is defense-in-depth so a
        // caller that skipped canonicalEmail still matches. A service account (V41) is never
        // returned — it falls into the ordinary unknown_email branch at login/reset request.
        Users.selectAll()
            .where { (Users.email eq canonicalEmail(email)) and human() }
            .toList()
            .singleOrNull()
            ?.let { it[Users.id].value to it.toUser(featuresOf(it[Users.id].value)) }
    }

    // The plaintext rules (min length, bcrypt byte ceiling) are route-side by necessity:
    // only the bcrypt hash ever reaches the service.
    suspend fun updatePassword(id: UInt, passwordHash: String, expectedAuthVersion: Long? = null): Int = suspendTransaction(database) {
        updatePasswordInTransaction(id, passwordHash, expectedAuthVersion)
    }

    /**
     * Set the per-user language (V18), returning its locked predecessor for truthful audit
     * deltas, or null when the row is absent. Same-value writes remain idempotent.
     */
    suspend fun setLanguage(id: UInt, language: String): User? = suspendTransaction(database) {
        validateLanguage(language) // re-checked service-side so direct callers stay guarded
        val previous = lockedUser(id) ?: return@suspendTransaction null
        Users.update({ (Users.id eq id) and human() }) {
            it[Users.language] = language
        }
        previous
    }

    suspend fun list(filter: UserListFilter, paging: PageRequest): UserListResult =
        suspendTransaction(database) {
            val predicate: Op<Boolean> = buildPredicate(filter) and human()
            val total = Users.selectAll().where { predicate }.count()
            val rows = Users.selectAll()
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .toList()
            // One batch query for the page's disabled sets — not a per-row lookup.
            val featuresByUser = disabledFeaturesByUserIds(rows.map { it[Users.id].value })
            val items = rows.map { row ->
                val id = row[Users.id].value
                row.toUser(featuresByUser[id].orEmpty().toSet()).toResponse(id)
            }
            UserListResult(items = items, total = total)
        }

    /**
     * Wholesale-replace the disabled-feature set under its owning user row's lock. Every
     * cooperating writer locks that row before reading flags, so the returned predecessor
     * and the replacement are one transition even when requests overlap. Null means absent.
     */
    suspend fun setDisabledFeatures(id: UInt, features: Set<Feature>): User? = suspendTransaction(database) {
        val previous = lockedUser(id) ?: return@suspendTransaction null
        features.forEach { f ->
            UserDisabledFeatures.upsert {
                it[userId] = id
                it[feature] = f.name
            }
        }
        val keep = features.map { it.name }
        if (keep.isEmpty()) {
            UserDisabledFeatures.deleteWhere { UserDisabledFeatures.userId eq id }
        } else {
            UserDisabledFeatures.deleteWhere { (UserDisabledFeatures.userId eq id) and (UserDisabledFeatures.feature notInList keep) }
        }
        previous
    }

    /** Outcome of a last-admin-guarded deletion (see [deleteGuarded]). */
    enum class GuardedMutation { DONE, NOT_FOUND, LAST_ADMIN }

    /** Identity updates return the actual predecessor from the same locked transaction. */
    sealed interface GuardedUpdate {
        data class Updated(val previous: User) : GuardedUpdate
        data object NotFound : GuardedUpdate
        data object LastAdmin : GuardedUpdate
    }

    /**
     * Lock administrators in ID order BEFORE the target when a write can remove an admin.
     * Taking the target first would deadlock two concurrent demotions. Promotion/name-only
     * ADMIN writes need only the target: they never reduce the active administrator count.
     */
    suspend fun updateGuarded(id: UInt, name: String, email: String, role: UserRole): GuardedUpdate =
        suspendTransaction(database) {
            validateNameAndEmail(name, email)
            val otherAdminExists = role == UserRole.ADMIN || lockedOtherAdminExists(id)
            val previous = lockedUser(id) ?: return@suspendTransaction GuardedUpdate.NotFound
            if (previous.role == UserRole.ADMIN && !otherAdminExists) {
                return@suspendTransaction GuardedUpdate.LastAdmin
            }
            Users.update({ (Users.id eq id) and human() }) {
                it[Users.name] = name
                it[Users.email] = canonicalEmail(email)
                it[Users.role] = role.name
                // Preserve the database-side epoch comparison as defense in depth.
                it[Users.authVersion] = Case().When(
                    (Users.email neq canonicalEmail(email)) or (Users.role neq role.name),
                    Users.authVersion + 1,
                ).Else(Users.authVersion)
            }
            GuardedUpdate.Updated(previous)
        }

    /** Soft delete uses the same admin-before-target lock order as a demotion. */
    suspend fun deleteGuarded(id: UInt): GuardedMutation = suspendTransaction(database) {
        val otherAdminExists = lockedOtherAdminExists(id)
        val previous = lockedUser(id) ?: return@suspendTransaction GuardedMutation.NOT_FOUND
        if (previous.role == UserRole.ADMIN && !otherAdminExists) {
            return@suspendTransaction GuardedMutation.LAST_ADMIN
        }
        Users.update({ (Users.id eq id) and human() }) {
            it[markedAsDeleted] = true
        }
        GuardedMutation.DONE
    }

    private suspend fun lockedUser(id: UInt): User? =
        Users.selectAll()
            .where { (Users.id eq id) and human() }
            .forUpdate()
            .toList()
            .singleOrNull()
            ?.let { it.toUser(featuresOf(id)) }

    // FOR UPDATE on the active-admin rows serializes concurrent admin mutations: the second
    // transaction blocks on the first's locks and re-evaluates the predicate after its commit.
    // Record a surviving OTHER admin, not a count that assumes the target was in this snapshot:
    // a concurrent promotion can turn a formerly excluded target into an admin before we lock it.
    // A service account can never be ADMIN (the V41 CHECK constraint), so human() vs active()
    // is moot here — kept for consistency with every other management-read predicate.
    private suspend fun lockedOtherAdminExists(targetId: UInt): Boolean =
        Users.selectAll()
            .where { (Users.role eq UserRole.ADMIN.name) and human() }
            .orderBy(Users.id)
            .forUpdate()
            .toList()
            .any { it[Users.id].value != targetId }

    private fun buildPredicate(filter: UserListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and (Users.name.containsNormalized(it)) }
        filter.email?.takeIf { it.isNotBlank() }?.let { op = op and (Users.email.containsNormalized(it)) }
        filter.role?.let { op = op and (Users.role eq it.name) }
        filter.feature?.let { f ->
            // The route guarantees featureEnabled is non-null whenever feature is set.
            val disabled = UserDisabledFeatures
                .select(UserDisabledFeatures.userId)
                .where { UserDisabledFeatures.feature eq f.name }
            op = op and if (filter.featureEnabled == false) {
                Users.id inSubQuery disabled
            } else {
                Users.id notInSubQuery disabled
            }
        }
        return op
    }

    /** Bootstrap: rotate a user's password only while they still carry [expectedHash]. */
    suspend fun rotatePasswordIfHashMatches(email: String, expectedHash: String, newHash: String): Int =
        suspendTransaction(database) {
            Users.update({ (Users.email eq email) and (Users.passwordHash eq expectedHash) and active() }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = System.currentTimeMillis()
                it[authVersion] = authVersion + 1
            }
        }

    /** Bootstrap: how many active accounts still carry [hash] (the well-known seed password). */
    suspend fun countActiveWithPasswordHash(hash: String): Long = suspendTransaction(database) {
        Users.selectAll().where { (Users.passwordHash eq hash) and active() }.count()
    }

    private fun active(): Op<Boolean> = Users.markedAsDeleted eq false

    /**
     * Active AND not a service account (V41) — the predicate behind every human-management
     * read/write: `read`, `findWithIdByEmail`, `list`, and `lockedUser` (and therefore every
     * mutation built on it — `setLanguage`/`setDisabledFeatures`/`updateGuarded`/
     * `deleteGuarded`). A service account is 404 across the whole `/api/v1/users` surface and
     * never counted in the list. `rotatePasswordIfHashMatches` and `countActiveWithPasswordHash`
     * keep using [active] instead, since they look for the seed admin's hash, which a service
     * account can never carry (`seedNeedsRotation` goes through `findWithIdByEmail` and
     * therefore this predicate — harmless, the seed admin is human).
     */
    private fun human(): Op<Boolean> = active() and (Users.serviceAccount eq false)

    private suspend fun featuresOf(id: UInt): Set<Feature> =
        UserDisabledFeatures.selectAll()
            .where { UserDisabledFeatures.userId eq id }
            .map { Feature.valueOf(it[UserDisabledFeatures.feature]) }
            .toList()
            .toSet()

    private suspend fun disabledFeaturesByUserIds(ids: List<UInt>): Map<UInt, List<Feature>> =
        if (ids.isEmpty()) emptyMap()
        else UserDisabledFeatures.selectAll()
            .where { UserDisabledFeatures.userId inList ids }
            .toList()
            .groupBy({ it[UserDisabledFeatures.userId].value }, { Feature.valueOf(it[UserDisabledFeatures.feature]) })

    private fun ResultRow.toUser(disabledFeatures: Set<Feature> = emptySet()): User = User(
        name = this[Users.name],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        role = UserRole.valueOf(this[Users.role]),
        disabledFeatures = disabledFeatures,
        passwordChangedAt = this[Users.passwordChangedAt],
        language = this[Users.language],
        authVersion = this[Users.authVersion],
        serviceAccount = this[Users.serviceAccount],
    )
}

private val serviceAccountSecretRandom = SecureRandom()

/**
 * A discarded random 256-bit base64url secret nobody holds — hashed once and never verified.
 * bcrypt is CPU-bound (deliberately, cost 12): callers must compute this BEFORE opening the
 * service account's insert transaction, never inside it, so the hash never pins a pooled
 * connection or a table lock (`IntegrationClientService.create`'s V27/V28 lock, this feature's
 * own `insertServiceAccountInTransaction` callers) for the duration of a bcrypt round.
 */
internal suspend fun discardedServiceAccountPasswordHash(): String {
    val bytes = ByteArray(32).also(serviceAccountSecretRandom::nextBytes)
    val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    return hashPassword(secret)
}

/**
 * Inserts a service account (2.15.0): role USER, never loginable. The caller owns the
 * transaction — the [updatePasswordInTransaction] precedent — and must supply [passwordHash]
 * precomputed via [discardedServiceAccountPasswordHash] BEFORE opening it, so bcrypt's CPU work
 * never runs inside a database transaction/lock. Written only by `IntegrationClientService` so
 * an integration client's entity writes carry an ordinary `created_by`. The synthetic
 * `@toadie.invalid` address is NOT validated against the human email rules (`validateEmail`
 * rejects that whole domain for everything else) — collision-freedom against
 * `uq_users_email_active` comes from the integration client id baked into the address, not from
 * the usual grammar checks. `name` still goes through the ordinary ≤50-character rule. No
 * `user_disabled_features` row: MFA is login-scoped, and a service account never logs in.
 */
internal suspend fun insertServiceAccountInTransaction(name: String, email: String, passwordHash: String): UInt {
    if (name.isBlank()) throw BadRequestException("Name must not be blank")
    if (name.length > MAX_NAME_LENGTH) throw BadRequestException("Name must be at most $MAX_NAME_LENGTH characters")
    return with(UserService.Users) {
        val newRecord = insert {
            it[UserService.Users.name] = name
            it[UserService.Users.email] = canonicalEmail(email)
            it[UserService.Users.passwordHash] = passwordHash
            it[role] = UserRole.USER.name
            it[language] = "en"
            it[serviceAccount] = true
        }
        newRecord[id].value
    }
}

/**
 * Caller owns the transaction: reset-token consumption and credentials must commit together.
 * A service account (V41) is never matched — it has no password to reset and no epoch worth
 * advancing, so both the admin password PUT and reset confirmation see zero affected rows.
 */
internal suspend fun updatePasswordInTransaction(
    id: UInt,
    passwordHash: String,
    expectedAuthVersion: Long?,
    changedAt: Long = System.currentTimeMillis(),
): Int = with(UserService.Users) {
    update({
        (UserService.Users.id eq id) and (markedAsDeleted eq false) and (serviceAccount eq false) and
            (expectedAuthVersion?.let { authVersion eq it } ?: Op.TRUE)
    }) {
        it[UserService.Users.passwordHash] = passwordHash
        it[passwordChangedAt] = changedAt
        it[authVersion] = authVersion + 1
    }
}
