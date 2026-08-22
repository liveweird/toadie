package ch.nokillswit.users

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.MAX_PASSWORD_BYTES
import ch.nokillswit.auth.exceedsBcryptLimit
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireSelfOrAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.put
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
            put<Users.Id.Password> { route ->
                val caller = call.caller()
                requireSelfOrAdmin(caller, route.parent.id)
                val req = call.receive<PasswordUpdateRequest>()
                // Changing one's OWN password always requires the current one (even for an admin);
                // an admin resetting somebody else's does not. Read before update so a wrong
                // current password never mutates anything. Checked BEFORE the length validation
                // so 403 wins over 400 (the convention everywhere else).
                if (caller.userId == route.parent.id) {
                    val existing = userService.read(route.parent.id)
                        ?: throw NotFoundException("User not found")
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
                val updated = userService.updatePassword(route.parent.id, hashPassword(req.password))
                if (updated == 0) {
                    throw NotFoundException("User not found")
                }
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
