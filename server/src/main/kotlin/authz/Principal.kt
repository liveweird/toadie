package ch.nokillswit.authz

import ch.nokillswit.users.UserRole
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

data class CallerPrincipal(
    val userId: UInt,
    val email: String,
    /** Additional roles — every caller is implicitly a regular user; empty = no extra privileges. */
    val roles: Set<UserRole>,
)

fun ApplicationCall.caller(): CallerPrincipal {
    val principal = principal<JWTPrincipal>()
        ?: throw UnauthorizedException()
    val email = principal.payload.getClaim("email").asString()
        ?: throw UnauthorizedException("Missing email claim")
    val userIdLong = principal.payload.getClaim("userId").asLong()
        ?: throw UnauthorizedException("Missing userId claim")
    val roleNames = principal.payload.getClaim("roles").asList(String::class.java)
        ?: throw UnauthorizedException("Missing roles claim")
    val roles = roleNames.map { name ->
        runCatching { UserRole.valueOf(name) }
            .getOrElse { throw UnauthorizedException("Unknown role $name") }
    }.toSet()
    return CallerPrincipal(
        userId = userIdLong.toUInt(),
        email = email,
        roles = roles,
    )
}
