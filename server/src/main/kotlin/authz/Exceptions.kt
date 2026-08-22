package ch.nokillswit.authz

class UnauthorizedException(message: String = "Authentication required") : RuntimeException(message)

class ForbiddenException(message: String = "Forbidden") : RuntimeException(message)

/**
 * Addressed resource does not exist (or is soft-deleted / lives under a different parent path)
 * → 404 with the given detail. Routes throw it from read preambles and zero-row mutation
 * results instead of hand-rolling a respondProblem + return.
 */
class NotFoundException(message: String = "Resource not found") : RuntimeException(message)

/**
 * Requested action conflicts with the resource's current state (e.g. an invalid status
 * transition, or a duplicate of an in-progress resource). [instance] optionally points at the
 * conflicting resource (an API path) and rides the ProblemDetail `instance` field, so clients
 * can link to it.
 */
class ConflictException(
    message: String = "Conflict",
    val instance: String? = null,
) : RuntimeException(message)

/**
 * Caller-specific throttling (e.g. the per-account login lockout) → 429 with the given detail.
 * Distinct from the per-IP RateLimit plugin's bodiless 429, which the StatusPages
 * `status(TooManyRequests)` handler completes with a generic problem body.
 */
class TooManyRequestsException(message: String = "Too many requests") : RuntimeException(message)
