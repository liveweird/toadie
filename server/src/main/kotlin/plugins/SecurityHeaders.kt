package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

// Strict Content-Security-Policy for the SPA + JSON API (production single-origin serving).
// - script-src 'self': the built SPA bundle is external/hashed; index.html has no inline app scripts.
// - style-src 'unsafe-inline': REQUIRED by Mantine (emotion CSS-in-JS) + the app's inline style={{}} usage.
// - img-src 'self' data:: local assets + data URIs; remote images in user markdown won't load (acceptable).
// - connect-src 'self': the API is same-origin and the SPA opens no WebSocket.
private const val APP_CSP =
    "default-src 'self'; " +
    "script-src 'self'; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; " +
    "font-src 'self'; " +
    "connect-src 'self'; " +
    "object-src 'none'; " +
    "base-uri 'self'; " +
    "form-action 'self'; " +
    "frame-ancestors 'none'"

// Defense-in-depth security response headers. The code is already XSS-safe (React escaping +
// react-markdown with no raw HTML); these headers contain the blast radius of any future
// regression and harden against clickjacking / MIME sniffing / referrer leakage.
fun Application.configureSecurityHeaders() {
    // Setup, not Plugins: in production mode the HttpsRedirect plugin (configureHttp) answers a
    // plain-HTTP request with its 301 from the Plugins phase, and appending to an already
    // committed response throws — every redirected request used to log an ERROR stack trace and
    // the 301 went out without these headers (Lettuce's v3.6.1 finding; ProductionHttpTest pins
    // it here). Setup runs before any plugin can respond, so the redirect carries them too.
    intercept(ApplicationCallPipeline.Setup) {
        val headers = call.response.headers
        if (headers["X-Content-Type-Options"] == null) { // set once per call
            headers.append("X-Content-Type-Options", "nosniff")
            headers.append("X-Frame-Options", "DENY")
            headers.append("Referrer-Policy", "no-referrer")
            headers.append("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()")
            // Swagger UI (/openapi) bootstraps with inline script/style; exclude it from the strict CSP.
            if (!call.request.path().startsWith("/openapi")) {
                headers.append("Content-Security-Policy", APP_CSP)
            }
        }
    }
}
