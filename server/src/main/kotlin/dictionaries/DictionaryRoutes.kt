package ch.nokillswit.dictionaries

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/dictionaries/{dictionary}")
class Dictionaries(val dictionary: String)

fun Application.configureDictionaryRoutes() {
    val dictionaryService = attributes[DictionaryServiceKey]

    routing {
        authenticate {
            get<Dictionaries> { route ->
                call.caller()
                val dict = Dictionary.fromSlug(route.dictionary)
                    ?: throw NotFoundException("Unknown dictionary")
                call.respond(HttpStatusCode.OK, DictionaryEntryList(items = dictionaryService.read(dict)))
            }
            put<Dictionaries> { route ->
                val caller = call.caller()
                // Guard before slug resolution: a non-admin probe gets a uniform 403 whether
                // or not the slug exists (the guard-before-read idiom).
                requireAdmin(caller)
                val dict = Dictionary.fromSlug(route.dictionary)
                    ?: throw NotFoundException("Unknown dictionary")
                val request = call.receive<DictionaryUpdateRequest>()
                validateDictionaryUpdate(request)
                val counts = dictionaryService.replace(dict, request)
                audit(
                    "dictionary.updated",
                    "byUserId" to caller.userId.toLong(),
                    "dictionary" to dict.name,
                    "added" to counts.added,
                    "renamed" to counts.renamed,
                    "removed" to counts.removed,
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
