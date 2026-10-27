// A throwaway OpenID Connect provider for the SSO e2e journeys (e2e/compose.sso.yaml). It is NOT
// a real IdP: it trusts whatever email is typed on its sign-in page. Node built-ins only, no npm
// dependencies, so the plain node:alpine image can run it straight from a read-only mount.
//
// Endpoints: discovery, /authorize (a tiny form), /authorize/complete (Continue / Cancel),
// /token (client_secret_basic + PKCE S256), /jwks (RS256), /healthz.
//
// Env:
//   STUB_IDP_PORT           listen port                                   (default 18090)
//   STUB_IDP_ISSUER         the `iss` claim AND the in-network origin the app calls for
//                           /token and /jwks; must equal the app's SSO_ISSUER
//                                                                         (default http://stub-idp:18090)
//   STUB_IDP_PUBLIC_URL     the browser-facing origin of /authorize       (default http://localhost:18090)
//   STUB_IDP_CLIENT_ID / STUB_IDP_CLIENT_SECRET   the one registered confidential client
//
// Logging: one line per request, method and path only — never a code, token or query string.
import { createServer } from "node:http";
import { createHash, generateKeyPairSync, randomBytes, sign, timingSafeEqual } from "node:crypto";
import { URL, URLSearchParams } from "node:url";

const PORT = Number(process.env.STUB_IDP_PORT ?? 18090);
const ISSUER = (process.env.STUB_IDP_ISSUER ?? "http://stub-idp:18090").replace(/\/+$/, "");
const PUBLIC_URL = (process.env.STUB_IDP_PUBLIC_URL ?? "http://localhost:18090").replace(/\/+$/, "");
const CLIENT_ID = process.env.STUB_IDP_CLIENT_ID ?? "toadie-e2e";
const CLIENT_SECRET = process.env.STUB_IDP_CLIENT_SECRET ?? "e2e-only-not-a-secret";
const CODE_TTL_MS = 5 * 60 * 1000;
const TOKEN_TTL_SECONDS = 300;

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const KID = randomBytes(8).toString("hex");
const JWK = { kty: "RSA", use: "sig", alg: "RS256", kid: KID, ...publicKey.export({ format: "jwk" }) };

/** code -> { email, name, nonce, codeChallenge, redirectUri, clientId, expires } */
const codes = new Map();

const b64url = (input) => Buffer.from(input).toString("base64url");

function sha256Hex(text) {
  return createHash("sha256").update(text).digest("hex");
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

function json(res, status, body, extra = {}) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra });
  res.end(JSON.stringify(body));
}

function html(res, status, body) {
  res.writeHead(status, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body);
}

function redirect(res, location) {
  res.writeHead(302, { Location: location, "Cache-Control": "no-store" });
  res.end();
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 64 * 1024) {
        reject(new Error("body too large"));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function signIdToken(claims) {
  const header = b64url(JSON.stringify({ alg: "RS256", kid: KID, typ: "JWT" }));
  const payload = b64url(JSON.stringify(claims));
  const signature = sign("RSA-SHA256", Buffer.from(`${header}.${payload}`), privateKey);
  return `${header}.${payload}.${b64url(signature)}`;
}

/** The authorization-request parameters the form must carry through to /authorize/complete. */
const CARRIED = ["client_id", "redirect_uri", "state", "nonce", "code_challenge", "code_challenge_method", "response_type", "scope"];

function authorizeProblem(params) {
  if (params.get("client_id") !== CLIENT_ID) return "unknown client_id";
  if (params.get("response_type") !== "code") return "response_type must be code";
  if (params.get("code_challenge_method") !== "S256" || !params.get("code_challenge")) return "PKCE S256 is required";
  if (!params.get("redirect_uri")) return "redirect_uri is required";
  return null;
}

function authorizePage(params) {
  const hidden = CARRIED.map((key) => `<input type="hidden" name="${key}" value="${escapeHtml(params.get(key) ?? "")}">`).join("\n      ");
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>Stub identity provider</title></head>
<body>
  <main>
    <h1>Stub identity provider</h1>
    <p>Test-only sign-in. Whatever you type is trusted.</p>
    <form method="get" action="/authorize/complete">
      ${hidden}
      <p><label>Email <input type="email" name="email" value="sso-user@example.test" required></label></p>
      <p><label>Name <input type="text" name="name" value="SSO User"></label></p>
      <p>
        <button type="submit" name="action" value="continue">Continue</button>
        <button type="submit" name="action" value="cancel" formnovalidate>Cancel</button>
      </p>
    </form>
  </main>
</body>
</html>`;
}

function handleDiscovery(res) {
  json(res, 200, {
    issuer: ISSUER,
    authorization_endpoint: `${PUBLIC_URL}/authorize`,
    token_endpoint: `${ISSUER}/token`,
    jwks_uri: `${ISSUER}/jwks`,
    response_types_supported: ["code"],
    subject_types_supported: ["public"],
    id_token_signing_alg_values_supported: ["RS256"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["client_secret_basic"],
  });
}

function handleAuthorize(res, url) {
  const problem = authorizeProblem(url.searchParams);
  if (problem) return html(res, 400, `<!doctype html><title>Bad request</title><p>${escapeHtml(problem)}</p>`);
  return html(res, 200, authorizePage(url.searchParams));
}

function handleComplete(res, url) {
  const params = url.searchParams;
  const problem = authorizeProblem(params);
  if (problem) return html(res, 400, `<!doctype html><title>Bad request</title><p>${escapeHtml(problem)}</p>`);
  const target = new URL(params.get("redirect_uri"));
  const state = params.get("state");
  if (state) target.searchParams.set("state", state);
  if (params.get("action") !== "continue") {
    target.searchParams.set("error", "access_denied");
    return redirect(res, target.toString());
  }
  const code = randomBytes(24).toString("base64url");
  const email = (params.get("email") ?? "").trim();
  codes.set(code, {
    email,
    name: (params.get("name") ?? "").trim(),
    nonce: params.get("nonce") ?? "",
    codeChallenge: params.get("code_challenge"),
    redirectUri: params.get("redirect_uri"),
    clientId: params.get("client_id"),
    expires: Date.now() + CODE_TTL_MS,
  });
  target.searchParams.set("code", code);
  return redirect(res, target.toString());
}

/** client_secret_basic: base64("urlencode(id):urlencode(secret)"), RFC 6749 section 2.3.1. */
function basicCredentials(req) {
  const header = req.headers.authorization ?? "";
  if (!header.startsWith("Basic ")) return null;
  const decoded = Buffer.from(header.slice(6), "base64").toString("utf8");
  const colon = decoded.indexOf(":");
  if (colon < 0) return null;
  try {
    return {
      id: decodeURIComponent(decoded.slice(0, colon).replace(/\+/g, "%20")),
      secret: decodeURIComponent(decoded.slice(colon + 1).replace(/\+/g, "%20")),
    };
  } catch {
    return null;
  }
}

async function handleToken(req, res) {
  const credentials = basicCredentials(req);
  if (!credentials || !safeEqual(credentials.id, CLIENT_ID) || !safeEqual(credentials.secret, CLIENT_SECRET)) {
    return json(res, 401, { error: "invalid_client" }, { "WWW-Authenticate": 'Basic realm="stub-idp"' });
  }
  const form = new URLSearchParams(await readBody(req));
  const entry = codes.get(form.get("code") ?? "");
  codes.delete(form.get("code") ?? ""); // single use, whether or not the rest checks out
  const verifierHash = createHash("sha256").update(form.get("code_verifier") ?? "").digest("base64url");
  const valid =
    form.get("grant_type") === "authorization_code" &&
    entry &&
    entry.expires > Date.now() &&
    entry.clientId === CLIENT_ID &&
    entry.redirectUri === form.get("redirect_uri") &&
    form.get("code_verifier") &&
    safeEqual(verifierHash, entry.codeChallenge);
  if (!valid) return json(res, 400, { error: "invalid_grant" });

  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: ISSUER,
    aud: CLIENT_ID,
    azp: CLIENT_ID,
    sub: `stub|${sha256Hex(entry.email.toLowerCase())}`,
    email: entry.email,
    email_verified: true,
    name: entry.name,
    nonce: entry.nonce,
    iat: now,
    exp: now + TOKEN_TTL_SECONDS,
  };
  return json(res, 200, {
    access_token: randomBytes(16).toString("base64url"),
    token_type: "Bearer",
    expires_in: TOKEN_TTL_SECONDS,
    id_token: signIdToken(claims),
  });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", PUBLIC_URL);
  console.log(`${req.method} ${url.pathname}`);
  try {
    if (req.method === "GET" && url.pathname === "/.well-known/openid-configuration") return handleDiscovery(res);
    if (req.method === "GET" && url.pathname === "/authorize") return handleAuthorize(res, url);
    if (req.method === "GET" && url.pathname === "/authorize/complete") return handleComplete(res, url);
    if (req.method === "POST" && url.pathname === "/token") return await handleToken(req, res);
    if (req.method === "GET" && url.pathname === "/jwks") return json(res, 200, { keys: [JWK] });
    if (req.method === "GET" && url.pathname === "/healthz") return json(res, 200, { status: "ok" });
    return json(res, 404, { error: "not_found" });
  } catch {
    return json(res, 500, { error: "server_error" });
  }
});

// Drop expired codes so an abandoned attempt cannot grow the map for the life of the container.
setInterval(() => {
  const now = Date.now();
  for (const [code, entry] of codes) if (entry.expires <= now) codes.delete(code);
}, 60_000).unref();

server.listen(PORT, "0.0.0.0", () => {
  console.log(`stub-idp listening on ${PORT} (issuer ${ISSUER})`);
});
