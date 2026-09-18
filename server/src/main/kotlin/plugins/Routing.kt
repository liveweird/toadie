package ch.nokillswit.plugins

import ch.nokillswit.authz.NotFoundException

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val staticDir = environment.config.propertyOrNull("web.staticDir")?.getString()?.takeIf { it.isNotBlank() }

    routing {
        // Reserve machine API paths even when disabled; never answer an API request with SPA HTML.
        route("/integration/{path...}") {
            handle { throw NotFoundException("Integration endpoint not found") }
        }
        if (staticDir != null) {
            // Serve the built React SPA: hashed assets plus a fallback to index.html
            // so React Router owns the remaining URL space. When unset (local dev / tests),
            // the SPA is served by Vite; the integration namespace is still reserved above.
            singlePageApplication {
                filesPath = staticDir
                defaultPage = "index.html"
            }
        }
    }
}
