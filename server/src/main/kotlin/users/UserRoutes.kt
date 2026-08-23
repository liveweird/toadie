package ch.nokillswit.users

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.MAX_PASSWORD_BYTES
import ch.nokillswit.auth.exceedsBcryptLimit
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** Minimum accepted password length for create and change. */
const val MIN_PASSWORD_LENGTH = 10

/** Shared password rule for create and change: the minimum plus bcrypt's byte ceiling. */
internal fun validatePassword(password: String) {
    if (password.length < MIN_PASSWORD_LENGTH) {
        throw BadRequestException("Password must be at least $MIN_PASSWORD_LENGTH characters")
    }
    // Longer input would make bcrypt throw (a 500) — see MAX_PASSWORD_BYTES in auth/Passwords.kt.
    if (exceedsBcryptLimit(password)) {
        throw BadRequestException("Password must be at most $MAX_PASSWORD_BYTES bytes in UTF-8")
    }
}

/** Audit format for a roles set: comma-joined sorted names, "" = the plain-user empty set. */
private fun Set<UserRole>.joinedNames(): String = map { it.name }.sorted().joinToString(",")

/**
 * Name and email are identity/security-relevant (email is the login identifier); the
 * `user.updated` event carries deltas only for the fields that actually changed — and is
 * skipped entirely when neither did.
 */
private fun auditUserUpdated(byUserId: UInt, targetUserId: UInt, existing: User, name: String, email: String) {
    if (name == existing.name && email == existing.email) return
    val fields = mutableListOf<Pair<String, Any?>>(
        "byUserId" to byUserId.toLong(),
        "targetUserId" to targetUserId.toLong(),
    )
    if (name != existing.name) {
        fields += "nameFrom" to existing.name
        fields += "nameTo" to name
    }
    if (email != existing.email) {
        fields += "emailFrom" to existing.email
        fields += "emailTo" to email
    }
    audit("user.updated", *fields.toTypedArray())
}

@Serializable
@Resource("/api/v1/users")
class Users {
    @Serializable
    @Resource("{id}")
    class Id(val parent: Users = Users(), val id: UInt) {
        @Serializable
        @Resource("password")
        class Password(val parent: Id)
    }
}

fun Application.configureUserRoutes() {
    val userService = attributes[UserServiceKey]

    routing {
        authenticate {
            // Management surface: ADMIN-only end to end (list included) — regular users only
            // keep the self password change below.
            get<Users> {
                requireAdmin(call.caller())
                val paging = call.parsePaging(sortable = USER_SORT_FIELDS)
                val params = call.request.queryParameters
                val filter = UserListFilter(
                    name = params.optionalString("name"),
                    email = params.optionalString("email"),
                    role = params.optionalEnum<UserRole>("role"),
                )
                val result = userService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<Users> {
                val caller = call.caller()
                // Guarded BEFORE the body decodes — the guard needs nothing from the payload,
                // so a non-admin's malformed body stays 403, not the converter's 400.
                requireAdmin(caller)
                val req = call.receive<UserCreateRequest>()
                val name = sanitizeSingleLine(req.name, "Name")
                val email = canonicalEmail(req.email)
                validateNameAndEmail(name, email)
                validatePassword(req.password)
                val role = rolesToStored(req.roles)
                // The password arrives client-generated and leaves this handler only as a
                // bcrypt hash — no response ever carries plaintext. A duplicate active email
                // rides the V1 partial index into the central 23505 → 409.
                val user = User(name = name, email = email, passwordHash = hashPassword(req.password), role = role)
                val id = userService.create(user)
                audit(
                    "user.created",
                    "byUserId" to caller.userId.toLong(),
                    "newUserId" to id.toLong(),
                    "email" to email,
                    "roles" to (req.roles?.toSet() ?: emptySet()).joinedNames(),
                )
                call.response.header(HttpHeaders.Location, call.application.href(Users.Id(id = id)))
                call.respond(HttpStatusCode.Created, user.toResponse(id))
            }
            get<Users.Id> { route ->
                // Guard BEFORE read (the documented users idiom): an unauthorized caller gets a
                // uniform 403 whether or not the id exists.
                requireSelfOrAdmin(call.caller(), route.id)
                val user = userService.read(route.id).orNotFound("User")
                call.respond(HttpStatusCode.OK, user.toResponse(route.id))
            }
            put<Users.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val req = call.receive<UserUpdateRequest>()
                val existing = userService.read(route.id).orNotFound("User")
                val requestedRole = rolesToStored(req.roles)
                // Last-admin protection: demoting the final active administrator would lock
                // everyone out of the management surface. This pre-check only preserves the
                // response ORDER (409 before the payload's 400); the race-proof check runs
                // inside updateGuarded's transaction.
                if (existing.role == UserRole.ADMIN && requestedRole != UserRole.ADMIN &&
                    userService.countActiveAdmins() <= 1
                ) {
                    throw ConflictException("The last administrator cannot be demoted")
                }
                val name = sanitizeSingleLine(req.name, "Name")
                val email = canonicalEmail(req.email)
                validateNameAndEmail(name, email)
                when (userService.updateGuarded(route.id, name, email, requestedRole)) {
                    UserService.GuardedMutation.NOT_FOUND -> throw NotFoundException("User not found")
                    UserService.GuardedMutation.LAST_ADMIN ->
                        throw ConflictException("The last administrator cannot be demoted")
                    UserService.GuardedMutation.DONE -> Unit
                }
                auditUserUpdated(caller.userId, route.id, existing, name = name, email = email)
                if (requestedRole != existing.role) {
                    audit(
                        "user.roles_changed",
                        "byUserId" to caller.userId.toLong(),
                        "targetUserId" to route.id.toLong(),
                        "from" to existing.additionalRoles.joinedNames(),
                        "to" to req.roles.toSet().joinedNames(),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
            delete<Users.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                // Before the read, so it 403s uniformly: deleting your own account mid-session
                // is a lockout footgun, not a management action.
                if (caller.userId == route.id) {
                    throw ForbiddenException("You cannot delete your own account")
                }
                // Existence (404) and the last-admin state (409) are decided inside ONE
                // transaction — same outcome order as before, without the check/mutate race.
                when (userService.deleteGuarded(route.id)) {
                    UserService.GuardedMutation.NOT_FOUND -> throw NotFoundException("User not found")
                    UserService.GuardedMutation.LAST_ADMIN ->
                        throw ConflictException("The last administrator cannot be deleted")
                    UserService.GuardedMutation.DONE -> Unit
                }
                audit(
                    "user.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "targetUserId" to route.id.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            put<Users.Id.Password> { route ->
                val caller = call.caller()
                requireSelfOrAdmin(caller, route.parent.id)
                val req = call.receive<PasswordUpdateRequest>()
                // Changing one's OWN password always requires the current one (even for an admin);
                // an admin resetting somebody else's does not. Read before update so a wrong
                // current password never mutates anything. Checked BEFORE the length validation
                // so 403 wins over 400 (the convention everywhere else).
                if (caller.userId == route.parent.id) {
                    val existing = userService.read(route.parent.id).orNotFound("User")
                    if (req.currentPassword == null || !verifyPassword(req.currentPassword, existing.passwordHash)) {
                        audit(
                            "password.change_denied",
                            "targetUserId" to route.parent.id.toLong(),
                            "byUserId" to caller.userId.toLong(),
                            "reason" to "wrong_current_password",
                        )
                        throw ForbiddenException("Current password is missing or incorrect")
                    }
                }
                validatePassword(req.password)
                userService.updatePassword(route.parent.id, hashPassword(req.password)).orNotFound("User")
                audit(
                    "password.changed",
                    "targetUserId" to route.parent.id.toLong(),
                    "byUserId" to caller.userId.toLong(),
                    "selfChange" to (caller.userId == route.parent.id),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
