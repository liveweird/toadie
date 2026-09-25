# Connecting an AI agent over MCP

Toadie 2.15.0 serves the Port ontology to agents over the Model Context Protocol at
`POST /integration/mcp` (stateless Streamable HTTP, JSON responses). The endpoint is on
whenever the integration API is (`INTEGRATION_ENABLED=true`; the compose stack sets it).

## 1. Create a key

Sign in as an administrator, open **Integration clients**, choose the scope and add a client:

- **Read** — list blueprints, read and check entities, read the ontology revision.
- **Write** — additionally create, replace, import and delete entities (never blueprints).

Copy the key from the one-time reveal. Every client acts through its own service account, so
entities the agent writes show the client's name as their creator.

## 2. Point the agent at Toadie

Claude Code: copy [`mcp.json`](mcp.json) to the root of the repository the agent will scan as
`.mcp.json` and paste the key. Claude Desktop uses the same block under `mcpServers` in its
`claude_desktop_config.json`. Any MCP client that speaks Streamable HTTP with a bearer header
works the same way.

## 3. Scan a repository, write what you find

A typical first session:

1. `list_blueprints` — learn the schema: which properties and relations a `service`, an `api`
   or a `dataset` carries, and which are required.
2. `check_entities` — dry-run the documents the agent assembled from the repository; fix the
   `INVALID` rows using the returned findings.
3. `upsert_entity` (one at a time) or `import_entities` (up to 200 per call) — write them, keyed
   by blueprint + identifier, passing the repository file URL as `sourceUrl` so each entity keeps
   its provenance and the Errors page never reports it as source-less.
4. `list_entities` / `get_entity` — verify; `delete_entity` retires an entity the repository no
   longer has (refused while other entities still point at it).

Tool failures come back as tool results with `isError` and a structured `{code, message, …}`
body (`INVALID` with `findings`, `NOT_FOUND`, `CONFLICT` with `referrers`, `BAD_REQUEST`,
`BUDGET_EXCEEDED`, `TIMEOUT`), so the agent can correct itself. Rate limits and authentication
failures are HTTP-level, exactly as for the GraphQL endpoint.

The baseline blueprints the sample ontology ships (`../port/commerce-payments/`) are a good
target for a first scan: a `service` with `provides_apis`/`consumes_apis`, its `api`s, the
`system` it belongs to.
