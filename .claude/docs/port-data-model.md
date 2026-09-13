# Port data model — blueprints (offline reference)

**This is the local reference for Port.io's data model as far as Toadie implements it.** Toadie
is moving from Backstage's fixed System Model to Port's ontology in phases; phase 1 (v1.23.0) is
the **Blueprints** feature — user-definable entity kinds — as a standalone registry. Consult this
file when designing any blueprint/entity feature instead of browsing; the upstream pages it
snapshots are listed at the end — re-check upstream (and update this file) when adding a rule.

Where Port's documentation states no explicit rule, the assumption Toadie made is marked
**(assumption)** so the next phase can revisit it consciously.

## Vocabulary

- **Blueprint** — "the basic building block of your data model": the schema of one asset class
  (a microservice, a Kubernetes cluster, a cloud account). Port's equivalent of a Backstage
  `kind`, except users define them.
- **Property** — a typed field of a blueprint holding ingested data.
- **Relation** — a logical, directed connection between two blueprints ("a service *depends on*
  a package"). Relations, mirror properties and aggregation properties together are the
  "ontology" layer: descriptions and semantic relation titles are what give the schema meaning.
- **Entity** — an instance of a blueprint (phase 2, v1.24.0 — the `entities/` package; see
  "Entities" below).
- **Hierarchy relations (Toadie, v1.32.0)** — an optional per-blueprint map of hierarchy identifiers (active `HIERARCHY` dictionary values) to keys of this blueprint's own
  `many: false` relations, naming the entity hierarchy's parent link (phase 3, v1.25.0 — not a
  Port concept; see "Toadie extensions" below).
- **Meta-properties** — attributes every entity carries automatically, `$`-prefixed:
  `$identifier`, `$title`, `$team` (the entity's computed or direct ownership, real since
  v1.26.0), `$icon`, `$blueprint`, `$createdAt`, `$updatedAt`, `$createdBy`, `$updatedBy`. The
  `$` prefix is reserved: no user-defined property or relation identifier may start with it; a
  mirror-property path may END in one.

## The blueprint JSON

```json
{
  "identifier": "microservice",
  "title": "Microservice",
  "description": "A deployable unit of software owned by a team.",
  "icon": "Microservice",
  "schema": {
    "properties": {
      "language":   { "type": "string", "title": "Language" },
      "repository": { "type": "string", "format": "url", "title": "Repository" }
    },
    "required": ["language"]
  },
  "relations": {},
  "mirrorProperties": {},
  "calculationProperties": {},
  "aggregationProperties": {},
  "ownership": {}
}
```

| Field | Required | Rule |
| --- | --- | --- |
| `identifier` | yes | ≤ 100 chars. Charset `[A-Za-z0-9@_.:/=-]` **(assumption — Port's UI states it; no regex is documented)**. Unique among Toadie's active blueprints, case-insensitively (Toadie's partial-index convention); relation targets are byte-exact. |
| `title` | yes | ≤ 100 chars. |
| `description` | no | Tooltip text; ≤ 2000 in Toadie **(assumption)**. Write it "as if explaining to a new team member". |
| `icon` | no | The NAME of one of Port's 300+ built-in icons (`Microservice`, `Service`, `Kubernetes`, `GitHub`, …); a free string in Toadie, ≤ 100. |
| `schema.properties` | yes (may be empty) | `{ <propertyIdentifier>: PropertyDefinition }` — see below. |
| `schema.required` | yes (may be empty) | Property identifiers that entities must fill; every entry must name a key of `schema.properties`. |
| `relations` | no | `{ <relationIdentifier>: RelationDefinition }`. |
| `mirrorProperties` | no | `{ <id>: { title, path } }`. |
| `calculationProperties` | no | `{ <id>: { title, type, format?, spec?, calculation, colorized?, colors? } }`. |
| `aggregationProperties` | no | `{ <id>: { title, target, calculationSpec, query?, pathFilter? } }`. |
| `ownership` | no | `{ type: Direct \| Inherited, title?, path? }`. |
| `teamInheritance`, `changelogDestination` | — | Port platform features (enterprise team inheritance; webhook/Kafka change destinations). **Not modeled by Toadie** — a request carrying them is rejected as an unknown key. |

Property, mirror, calculation and aggregation identifiers share ONE namespace (Port entities
expose them all under `properties`); relation identifiers are a separate namespace. All follow
the identifier charset, ≤ 100 chars, and never start with `$`.

## Property definitions

Every property: `type` (required) ∈ `string | number | boolean | array | object`, plus optional
`title`, `description`, `icon`, `default` (a JSON value that must match the type, and the enum
when one is set). Unset fields are ABSENT, never `null`.

### string

```json
{ "type": "string", "title": "…", "format": "url", "default": "https://example.com",
  "minLength": 1, "maxLength": 32, "pattern": "^[a-z-]+$",
  "enum": ["a", "b"], "enumColors": { "a": "red", "b": "green" } }
```

- `format` ∈ `url | email | idn-email | user | team | date-time | timer | yaml | markdown | proto
  | ipv4 | ipv6`. `user`/`team` hold a Port user email / team identifier; `date-time` and
  `timer` hold ISO 8601 (`2022-04-18T11:44:15.345Z`; a timer fires `TIMER_EXPIRED` when it
  passes); `yaml`/`markdown`/`proto` hold document text.
- `date_format` (only with `format: date-time`) ∈ `relative | 12-hour | 24-hour |
  YYYY-MM-DD HH:mm` — display only.
- Validation: `minLength`, `maxLength` (`0 ≤ min ≤ max`), `pattern` (a regex — Toadie requires
  it to compile and caps it at 500 chars **(assumption)**).
- `spec` ∈ `open-api | async-api | embedded-url` turns the property into a viewer: Swagger UI
  over a URL/YAML, or an embedded page (`spec: embedded-url` with `format: url`); a protected
  embed adds `specAuthentication: { authorizationUrl, tokenUrl, clientId, authorizationScope[] }`.

### number

```json
{ "type": "number", "default": 7, "minimum": 0, "maximum": 50,
  "exclusiveMinimum": 0, "exclusiveMaximum": 100, "enum": [1, 2, 3], "enumColors": { "1": "red" } }
```

`minimum ≤ maximum`, `exclusiveMinimum < exclusiveMaximum`; Toadie rejects an inclusive and an
exclusive bound on the same side **(assumption — JSON Schema allows it, Port's UI does not)**.

### boolean

`{ "type": "boolean", "default": true }` — nothing else.

### array

```json
{ "type": "array", "items": { "type": "string", "format": "url" },
  "minItems": 0, "maxItems": 5, "uniqueItems": false, "default": ["https://example.com"] }
```

`items.type` ∈ `string | number | boolean | object`; `items.format`/`items.enum`/
`items.enumColors` follow the string/number rules. `minItems`/`maxItems` are not supported on
Port's API-only `union` arrays (Toadie does not model `union`).

### object

```json
{ "type": "object", "format": "labeled-url", "default": { "url": "…", "displayText": "…" },
  "properties": { "myKey": { "type": "number" } },
  "patternProperties": { "^S_": { "type": "string" } },
  "additionalProperties": true }
```

`format: labeled-url` = a `{ url, displayText? }` object; `spec` ∈ `open-api | async-api`
(an inline API definition). `properties`, `patternProperties` and `additionalProperties` are
open JSON-Schema sub-trees — Toadie stores them verbatim and checks only their shape (objects of
objects; `additionalProperties` a boolean or an object) and size.

### enum and enumColors

`enum` (string or number, and inside `items`) lists the closed value set; `enumColors` maps a
subset of those values (stringified) to one of Port's 14 colours:

`blue turquoise orange purple pink yellow green red darkGray lightGray bronze gold silver paleBlue`

The single-vs-list choice (`string` vs `array`) is permanent in Port once entities exist.

## Relations

```json
{ "relations": { "owningTeam": {
    "title": "Owned by", "description": "…", "target": "team", "required": false, "many": false } } }
```

- `target` names an existing blueprint (a blueprint may relate to itself). Deleting a targeted
  blueprint is refused by Toadie (409 naming the referrers) — Port's behaviour is comparable;
  renaming a blueprint's identifier rewrites every relation/aggregation target in place.
- `many: true` allows several target entities; `required: true` makes the relation mandatory on
  every entity. **`required` and `many` cannot both be true.**
- Give relations semantic titles (`Owned by`, `Runs in`, `Depends on` — not `team`, `env`).

## Mirror properties

`{ "mirrorProperties": { "ownerDomain": { "title": "Domain", "path": "system.domain.$title" } } }`

`path` chains relation identifiers and ends in a property of the final blueprint or a
meta-property (`$title`, `$identifier`, `$team`, `$createdAt`, `$updatedAt`). Toadie checks the
first segment is a relation of THIS blueprint and caps the chain at 10 segments **(assumption)**;
deeper segments are other blueprints' relations and are not resolved at WRITE time — Toadie
never validates a `path`'s later segments when the blueprint is saved. They ARE resolved at READ
time (phase 5, v1.27.0): each hop but the last is followed as a relation of the CURRENT
blueprint (single value, or every value of a `many` relation), landing on the target rows; the
final segment is read off every landed row. See "Computed properties" below for the full walk,
fan-out cap, and absent/shape rules.

## Calculation properties

```json
{ "calculationProperties": { "status": {
    "title": "Status", "type": "string", "calculation": ".properties.rawStatus",
    "colorized": true, "colors": { "OK": "green", "WARNING": "yellow", "CRITICAL": "red" } } } }
```

`type` ∈ the five property types; `format`/`spec` as for properties; `calculation` is a **jq**
expression over the entity (`.properties.x`, `.identifier`, `.relations.r`) — Toadie stores it
as text (1–10 000 chars, not parsed); `colors` values are the 14 colours. Evaluated at READ time
(phase 5, v1.27.0) with real jq 1.6 semantics (`net.thisptr:jackson-jq` 1.3.0): the first value
the expression emits wins, then is checked STRICTLY against the declared `type` — a mismatch,
or any evaluation failure, is simply ABSENT. See "Computed properties" below for the jq input
shape and `.claude/docs/security.md` "Computed-property evaluation (jq)" for the sandboxing
posture.

## Aggregation properties

```json
{ "aggregationProperties": { "openIssues": {
    "title": "Open issues", "target": "jiraIssue",
    "calculationSpec": { "calculationBy": "entities", "func": "count" },
    "query": { "combinator": "and", "rules": [ { "property": "status", "operator": "!=", "value": "Done" } ] },
    "pathFilter": [] } } }
```

- `target` names an existing blueprint; `calculationBy` ∈ `entities | property`.
- `entities` → `func` ∈ `count | average` (`average` takes `averageOf` ∈ `hour | day | week |
  month | total` and `measureTimeBy` — `$createdAt`, `$updatedAt` or a date property).
- `property` → `property` (a numeric property of the target) and `func` ∈ `average | sum | min |
  max | median`.
- `query` is Port's search-rule syntax (`combinator` + `rules`); `pathFilter` steers multi-hop
  traversal. Toadie stores both verbatim, checking only that rules/filters are objects.

Evaluated at READ time (phase 5, v1.27.0): the TARGET blueprint's active rows related to the
subject are found via direct relations or a `pathFilter` chain (in either direction), filtered
by `query`, then reduced by `calculationSpec`. See "Computed properties" below for the full
candidate-resolution, query-operator, and calculation rules.

## Ownership

`{ "ownership": { "type": "Direct", "title": "Owning team" } }` — entities MUST carry a `team`
value (string or array) naming one or more ACTIVE entities of the `_team` system blueprint
(v1.26.0, phase 4 — see "System blueprints" below). `{ "type": "Inherited", "path": "service.owningTeam" }` —
ownership is computed at read time by walking the `path` over single-valued relations to a
blueprint with Direct ownership; `path` is a dot-chain of relation identifiers (first must be
this blueprint's), and the final `team` is never stored (the column stays NULL). Absent = no
ownership constraint. **Ownership is informational only — it never gates permissions.** Any
authenticated user may read/modify any entity regardless of its ownership. The entity list/graph
`team` filter (v1.30.0) follows this SAME `path` walk to match against the effective team it
displays — an Inherited entity whose path does not resolve has no team and never matches.

## Entities (phase 2, v1.24.0)

Phase 2 adds **entities** — instances of a blueprint — as the new `entities/` package beside
the untouched Backstage `catalog/`. Every entity belongs to one blueprint, carries `properties`
typed by that blueprint's `schema`, and `relations` naming other entities of the target
blueprints. Any authenticated user may create/read/update/delete any entity (the catalog-file
shared-workspace rule, no `isAdmin` gate). Templates: `blueprints/*.kt` for the service/route
shape, `catalog/CatalogFileService.list`/`CatalogFileFilter.kt` for the paged list.

### The entity wire shape (from Port's public OpenAPI)

```json
{ "identifier": "checkout", "title": "Checkout", "icon": "Microservice", "team": ["payments"],
  "properties": { "language": "kotlin", "tier": 1, "ports": [8080] },
  "relations": { "domain": "commerce", "depends_on": ["catalog", "pricing"] } }
```

Responses add `blueprint` (the owning blueprint's identifier) + `blueprintId` + Toadie's usual
`id`/`createdBy`/`creatorName`/`creatorDeleted`/`createdAt`/`updatedAt` + `findings` (below).
Unset optionals are ABSENT, never `null` — the `blueprintJson` convention, reused verbatim.

| Field | Required | Rule |
| --- | --- | --- |
| `identifier` | yes | Port pattern `^(?!\.{1,2}$)[\p{L}0-9@_.+:\\/='-]+$` — unicode letters plus `+`, `'`, `\`, WIDER than the blueprint charset; Port caps it at 1000, Toadie at **200 (assumption)**. Unique PER BLUEPRINT, case-insensitively (the partial-index convention); the same identifier may be reused across different blueprints. |
| `title` | yes | ≤ 200 chars **(assumption)**; must not be blank. |
| `icon` | no | ≤ 100 chars — a Port icon NAME, free string (the blueprint convention). |
| `team` | no | `string \| string[]` (≤ 50 entries, each ≤ 100 chars **(assumption)**), stored and returned EXACTLY as sent. See "Ownership" above: the rule depends on the blueprint's `ownership` (Direct/absent → validated against `_team` entities; Inherited → server-computed at read time, never stored, supplied value rejected; see `entityFindings` table below). Absent when unset. |
| `properties` | no | `{ <propertyId>: JSON value }`, keyed by the blueprint's declared property ids; defaults to empty. |
| `relations` | no | `{ <relationId>: string \| string[] \| null }` — single vs many follows the blueprint relation's `many`; `null` means unset (never an error by itself). Defaults to empty. |

### Validation — `entityFindings`, a PURE function

Two layers, the phase-1 shape: `validateEntityRequest` enforces the blueprint-FREE shape rules
above (identifier grammar/length, title, `team` shape, key grammar, a 256 KiB document cap) as
an ordinary `400`. `entityFindings(document, definition, targetExists)` is the
BLUEPRINT-DEPENDENT rule table below — it never throws, so the exact same list backs both the
strict-save `400` (non-empty → one aggregated failure) and the `findings` field every GET/list
response carries (see "Lifecycle rules" below). `EntityFinding{code, field, message}`, `field` =
`properties.<id>` or `relations.<id>`.

| Case | Rule | Finding code |
| --- | --- | --- |
| property key ∉ `schema.properties` | rejected | `UNKNOWN_PROPERTY` |
| property key ∈ mirror/calculation/aggregation ids | COMPUTED in Port, never accepted as input | `COMPUTED_PROPERTY` |
| `schema.required` key absent or `null` | a non-required `null` = unset (stored as absent) | `REQUIRED_MISSING` |
| any `properties`/`relations` key with an explicit JSON `null` value | `toDocument()` drops every explicit null uniformly BEFORE `entityFindings` ever sees the document, so a `null` against an unrecognized key (e.g. `{"properties": {"unknownKey": null}}`) is stored as absent and never raises `UNKNOWN_PROPERTY`/`UNKNOWN_RELATION` either | (none — key silently unset) |
| string value | JSON string; `minLength`/`maxLength` by code points; `pattern` unanchored `containsMatchIn` (JSON-Schema semantics); `enum`; formats: `url` → `isAbsoluteUrl`; `email`/`idn-email` → non-empty local@domain on the LAST `@`; `date-time`/`timer` → `OffsetDateTime`/`Instant` parse; `ipv4` → four octets; `ipv6` → hex/colon literal via `InetAddress` (never DNS); `user`/`team`/`yaml`/`markdown`/`proto` → free text | `TYPE_MISMATCH` / `ENUM_MISMATCH` / `FORMAT_INVALID` / `LENGTH_OUT_OF_RANGE` / `PATTERN_MISMATCH` |
| number value | JSON number; `minimum`/`maximum` inclusive, `exclusive*` strict; `enum` numeric | `TYPE_MISMATCH` / `RANGE_OUT_OF_BOUNDS` / `ENUM_MISMATCH` |
| boolean value | JSON boolean | `TYPE_MISMATCH` |
| array value | JSON array; each element matches `items.type`/`items.enum`/`items.format`; `minItems`/`maxItems`; `uniqueItems` by structural equality | `TYPE_MISMATCH` / `ARRAY_SIZE` / `ARRAY_NOT_UNIQUE` |
| object value | JSON object; `format: labeled-url` → exactly `{url: absolute, displayText?}`; else verbatim — NO JSON-Schema evaluation of `properties`/`patternProperties`/`additionalProperties` (documented, matches the blueprint's own storage-only posture on those sub-trees) | `TYPE_MISMATCH` / `OBJECT_SHAPE` |
| relation key ∉ `relations` | rejected | `UNKNOWN_RELATION` |
| relation shape | `many: false` → string or null; `many: true` → array of distinct strings or null; else rejected | `RELATION_SHAPE` |
| `required: true` relation | non-null and (arrays) non-empty, else rejected | `RELATION_REQUIRED` |
| each relation target | `targetExists(targetBlueprint, targetIdentifier)` over ACTIVE entities of the relation's target blueprint, byte-exact (self allowed — Port allows self-relations) | `RELATION_TARGET_MISSING` |
| `team` field | Direct/absent ownership: each value (if supplied) must name an ACTIVE `_team` entity, case-insensitive; Inherited ownership: stored value (`null`) is absent and server-computed at read time; a supplied write value is rejected | `TEAM_TARGET_MISSING` / `TEAM_NOT_ALLOWED` |
| `properties.<id>` with `format: team` | each string/array-item value must name an ACTIVE `_team` entity, case-insensitive | `TEAM_TARGET_MISSING` |
| `properties.<id>` with `format: user` | each string/array-item value must name an ACTIVE `_user` entity, case-insensitive (email or identifier) | `USER_TARGET_MISSING` |

### Not modeled in phase 2

- Port's `upsert`/`merge`/`validation_only`/`create_missing_related_entities` write params —
  Toadie's POST/PUT are plain creates/replacements.
- The search-query relation form (naming a target by a Port search rule instead of an
  identifier) and `delete_dependents` (cascading deletes) — a relation target is always a
  byte-exact identifier, and DELETE never cascades past the referrer check below. An unknown
  top-level key on the request is `400` via the strict `DefaultJson` (the blueprint precedent).

### Computed properties (phase 5, v1.27.0)

`mirrorProperties`, `calculationProperties` and `aggregationProperties` are evaluated at READ
time by `entities/EntityComputed.kt` (orchestrator + mirror walk), `entities/EntityAggregation.kt`
(aggregation candidates + `calculationSpec`) and `entities/AggregationQuery.kt` (the `query`
rule engine) — pure, DB-free code running over the `EntityIndex` snapshot `EntityService`
already loaded for validation (`.claude/docs/persistence.md`). This reverses the phase-2
statement above: Toadie now computes their values on every `GET`/list/create.

**Wire shape.** Computed values live INSIDE the ordinary `properties` object of every
GET/list/create response — Port's own shape, never a sibling field: response
`properties = document.properties` (stored) merged with the computed values ON TOP, so a
computed id COLLIDES with and WINS OVER a stale stored key of the same name. `findings` is
unchanged: computed only over the STORED document, never the merged response — a stale stored
key keeps surfacing as a `COMPUTED_PROPERTY` finding (see the table above) until the entity's
next save strips it (the SPA does this before submitting). An unresolvable computed value is
simply ABSENT from `properties` — never `null`, never a finding of its own; only `entityFindings`
(write-time) can fail a save. `POST`/`PUT` sending a computed property id is UNCHANGED: still
`400` `COMPUTED_PROPERTY` — Toadie never accepts a computed value as input, only ever produces
one.

**Mirror walk.** `mirrorValue` walks every segment but the last of `path` as a relation of the
CURRENT blueprint, starting at the subject's own row: a `many: false` hop follows the single
target identifier, a `many: true` hop fans out over every value (capped at
`MAX_MIRROR_FANOUT = 1000` landed rows per hop) — up to `MAX_COMPUTED_HOPS = 10` hops (the same
budget `EntityOwnership.kt`'s Inherited walk uses one layer up). An unknown relation, an unknown
target blueprint, or a chain over the hop budget makes the WHOLE value absent; an individual
unresolved relation VALUE along the way is simply skipped, never a give-up. The terminal segment
is then read off every row the walk landed on: a `$`-prefixed terminal resolves one of
`$identifier`, `$title`, `$icon`, `$blueprint`, `$team` (the landed row's EFFECTIVE team,
resolved the same way as the entity's own), `$createdAt`, `$updatedAt` (epoch millis —
**assumption**) — any other meta-property, including `$createdBy`/`$updatedBy`, is absent;
otherwise the terminal must name a key of the landed blueprint's OWN `schema.properties`, read
from its STORED document — a terminal naming a COMPUTED id of the landed blueprint is absent
(never recursed into, so a mirror chain cannot chain into another mirror/calculation/
aggregation). Shape: no `many` hop anywhere in the chain → the single landed value (or absent if
nothing landed); any `many` hop → a `JsonArray` of every landed value, one level flattened when
a landed value is itself an array, structurally deduped in walk order (`[]` is a valid result
when the chain resolved but nothing landed — distinct from absent).

**Calculation.** `calculationValue` runs `calculation` as a real jq 1.6 expression (via
`net.thisptr:jackson-jq` 1.3.0, `gradle/libs.versions.toml`) over a JSON object shaped
`{identifier, title, blueprint, icon?, team?, properties, relations}` — the entity's STORED
`properties`/`relations` and its EFFECTIVE `team` (**assumption**: no `id` or timestamps in the
jq input, matching Port's own `.identifier`/`.properties.x`/`.relations.r` calculation
examples). The FIRST value the expression emits wins (`1, 2` yields `1`; `range(1e9)` yields
`0` instead of iterating); a JSON `null` output, no output at all, a compile/runtime error, or a
runaway recursion (`StackOverflowError`, e.g. `def f: f; f`) all mean absent, never a thrown
error. The winning output is then checked STRICTLY against the declared `type` (the same
structural check [`jsonMatchesType`] `entityFindings` itself uses): a shape mismatch is absent,
never coerced — `tostring`/`tonumber` are the admin's own tools to fix a mismatched expression.
A calculation whose evaluation exceeds the server's per-expression deadline (default 500 ms) is
likewise absent, and stays absent until its expression text is changed (v1.29.0 — see
`.claude/docs/security.md` "Computed-property evaluation (jq)"). See that section for the
sandboxing posture, `env`/`$ENV` shadowing, and the output-size cap.

**Aggregation.** `relatedEntities` finds every ACTIVE row of `target` related to the subject:
with NO `pathFilter`, DIRECT relations in EITHER direction — every entity of `target` naming the
subject (via `EntityIndex.inbound`) UNION every entity the subject's OWN relations name that
happens to be of blueprint `target` (a self-targeting aggregation counts both sides, documented);
each `pathFilter` entry `{fromBlueprint, path}` narrows to ONE direction instead:
`fromBlueprint == <subject's own blueprint>` walks FORWARD from the subject (fanning out on
`many`, ≤ `MAX_COMPUTED_HOPS` hops, the LAST relation must target `target`); `fromBlueprint ==
target` statically resolves `target --r1--> … --rn--> <subject's blueprint>` and then walks
BACKWARDS from the subject through `EntityIndex.inbound`, one hop at a time, matching each
step's expected source blueprint and relation id — the REVERSE direction, needed when the
relation is declared on `target` rather than the subject (e.g. a `system`'s `workload_replicas`
reading `workload → service → system`); any other `fromBlueprint`, or a malformed entry,
contributes nothing (**assumption**). Candidates are deduped by identifier, then filtered by
`query` (Port's `combinator`+`rules` search syntax, `entities/AggregationQuery.kt`): `null`
matches everything; `and` requires every rule (empty → true), `or` requires at least one
(empty → false), any other combinator → false; a nested rule (`combinator`+`rules`, no
`property`) recurses up to `MAX_QUERY_DEPTH = 10`; a leaf rule looks up `$identifier`/`$title`/
`$blueprint`/`$icon`/`$createdAt`/`$updatedAt`, `$team` (always an ARRAY — **assumption**), or a
STORED property, and evaluates one operator:

| Operator | Rule |
| --- | --- |
| `=` / `!=` | structural equality; an absent actual is never `=` anything (including an explicit `null` rule value), so `!=` against an absent actual is `true` |
| `>` `<` `>=` `<=` | numeric when both sides are JSON numbers, else lexicographic when both are strings, else `false` |
| `contains` / `doesNotContain` | array element match or string substring |
| `in` / `notIn` | `expected` must be an array; membership by structural equality |
| `isEmpty` / `isNotEmpty` | absent, `null`, `""`, `[]`, `{}` count as empty |
| `containsAny` | scalars treated as singleton lists, any shared element (**interpretation** — Port's docs do not spell out the multi-value shape) |
| anything else | `false` |

Matching candidates are reduced by `calculationSpec` (`applyCalculationSpec`):
`calculationBy: "entities"` → `func: "count"` is the row count (`0` is a value, not absent);
`func: "average"` (choice 3, **assumption**) = matched count ÷
`max(1, ceil((now − earliest measured timestamp) / period))`, where `period` is
`averageOf: hour|day|week|month` = `3600 s | 86 400 s | 7 × 86 400 s | 30 × 86 400 s`, and
`averageOf: total` is a plain count; `measureTimeBy` is `$createdAt`, `$updatedAt`, or an ISO
8601 date-time property (unparseable/missing timestamps are skipped; nothing measurable is
absent). `calculationBy: "property"` → `func: sum|min|max|average|median` over the JSON-number
values of `property` (non-numeric values skipped; none → absent). An integral result is emitted
as a JSON integer (`16`, never `16.0`).

**Scope.** Computed values are NOT stored (`.claude/docs/persistence.md`), so they are never
filterable, sortable, or matched by `q` on the entity list (`.claude/docs/list-endpoints.md`),
and they never appear on Entity graph nodes (`GET …/entities/graph` — a graph node carries only
`findings` as a count, never `properties`; the snapshot behind `update`/`graph` never widens for
or evaluates computed properties at all, see `.claude/docs/persistence.md`).

### Lifecycle rules

- **Blueprint delete with active entities is `409`**, naming the count — checked under the same
  `blueprints` table lock phase 1 already uses, before the existing referrer check.
- **Blueprint schema/relation EDITS go through unopposed** — entities are NOT re-validated at
  edit time (no grandfathering enforcement on write, the phase-1 posture). Instead, every
  `EntityService.list`/`read` re-runs `entityFindings` against the blueprint's CURRENT
  definition on every read, so an entity a blueprint edit left non-conformant shows up STALE —
  non-empty `findings` in its GET/list response — without any background job. Its next save
  (PUT) re-validates and is refused (`400`) until the findings clear.
- **A relation target must exist** at write time (`RELATION_TARGET_MISSING`, `400`); an entity
  that is the TARGET of another active entity's relation cannot be deleted (`409`, naming the
  referrers as `blueprint/identifier` — the phase-1 blueprint-target idiom, one level down).
- **Identifier rename cascades**: a PUT that changes `identifier` rewrites every OTHER active
  entity's `relations` naming the old identifier, in the same locked transaction (audited
  `cascaded`/`renamedFrom`, the phase-1 shape). Additionally, renaming a `_team` or `_user`
  entity cascades into the `team` column of every entity carrying a Direct/absent ownership,
  and into every property value with `format: team|user` across all entities.
- **Ownership targets cannot be deleted**: deleting a `_team` entity that is named by ANY
  active entity's `team` field or `format: team` property, or deleting a `_user` entity named
  by any `format: user` property, is `409` naming the referrers (the phase-1 blueprint-target
  idiom, expressed as `blueprint/identifier` — the referrer's location — from all three
  sources: `team` field, `format: team` properties, and `format: user` properties).

## Toadie extensions (not Port)

Phase 3 (v1.25.0) adds one Toadie-only field that has no equivalent in Port's own model, and phase 7
(v2.0.0) one Toadie-only READ surface over the model (the second bullet):

- **Hierarchy relations** — `Blueprint.hierarchyRelations: Map<String, String>?`, an optional
  map of hierarchy identifiers to relation keys. The keys are active values from the `HIERARCHY`
  dictionary (e.g. `"composition"`, `"deployment"`); the values are KEYs of the blueprint's own
  `relations` entries. The rule: each value must be a KEY of `relations`, and that relation
  must have `many: false` (a hierarchy parent is singular by definition) — naming an unknown
  hierarchy id, unknown relation, or a `many: true` relation is `400`. It is stored BESIDE the
  Port document, in its own column (V29, replaced by V34 map migration), never inside
  `definition`/`schema`/`relations` — so `toDefinition()`'s emitted Port JSON stays
  byte-identical to what phase 1 already produced, and any future Port export simply drops the
  column rather than needing to strip anything out of the document. Wire shape follows the
  blueprint convention: optional, NOT `nullable`, ABSENT when unset (never `null`);
  renaming/removing a named relation is not cross-checked (the same no-grandfathering-on-edit
  posture as the schema/relations themselves — see "Lifecycle rules" above).

  The **entity hierarchies** derive from it, purely: for each hierarchy identifier H in the
  blueprint's `hierarchyRelations` map, that hierarchy's tree is built from the relation key
  the map names — an entity's parent in hierarchy H is the target of its OWN blueprint's
  relation value for that key (a `many: false` relation, so at most one target per hierarchy);
  blueprints that name no relation key for hierarchy H in their `hierarchyRelations` are H's
  roots, and so is any entity whose relation value is unset or does not resolve. The entity
  graph (below) flags the edges that carry a relation matching a blueprint's `hierarchyRelations`
  value with `hierarchies: [<hierarchy-ids-that-name-this-relation>]`, so each hierarchy tree
  and the general relation graph are two views over the same data, never separately stored
  parent pointers.

- **Entity graph** — `GET /api/v1/entities/graph` renders entities and their relations together
  (`entities/EntityGraph.kt`, the `catalog/Graph.kt` counterpart one level down). Each node's id
  follows the grammar `"<blueprint>|<identifier>"` (`|` appears in neither the blueprint nor the
  entity identifier charset, so exactly one `|` per id and the split is unambiguous). Unlike the
  catalog graph, there are no virtual/MISSING nodes: an edge is emitted only when BOTH its ends
  are among the entities the `blueprint`/`q` filters selected, so a relation value naming a
  filtered-out or no-longer-resolving target simply contributes no edge — never a placeholder
  node. Each node carries `findings` as a plain COUNT (not the detailed list `GET`/list return)
  of the same `entityFindings` computation — the stale marker, condensed for a graph face.

  **Hierarchy and ownership edges** (v1.32.0, hierarchies; v1.26.0 phase 4, ownership): a
  relation edge carries `hierarchies: [<ids>]` (an array of hierarchy identifiers from the
  SOURCE blueprint's `hierarchyRelations` that name this relation key) and `ownership: false`
  when it is a regular entity-to-entity relation; it carries `hierarchies: []` and
  `ownership: true` when it is an entity-to-`_team` node via the entity's EFFECTIVE team
  value. When an entity carries an EFFECTIVE team value and that node is shown in the graph,
  one ownership edge is emitted per team value to the corresponding `_team|<id>` node (if also
  shown). A `_team` node also matches a `team` filter (single value, case-insensitive), so a
  team-filtered graph retains the team nodes to close ownership relations — since v1.30.0 the
  `team` filter matches an Inherited entity's EFFECTIVE team too (the same `ownership.path`
  walk the list uses), so it can appear in a team-filtered graph; a renamed or deleted `_team`
  affects inherited entities' stale findings AND their graph presence (an unresolvable path
  has no team and is dropped from a team-filtered graph).

- **Entity queries (v2.0.0)** — `GET /api/v1/entities/graph?query=…` and
  `POST /api/v1/entities/query/check` accept a read-only, openCypher-shaped query
  (`.claude/docs/entity-query-language.md`) whose labels are blueprint identifiers (case-folded),
  whose edge types are relation KEYS (byte-exact), hierarchy identifiers (the virtual
  child → parent edge each blueprint's `hierarchyRelations` names for that hierarchy) or the
  `$team` ownership pseudo-edge (entity → its EFFECTIVE `_team`), and whose `WHERE` sees the
  STORED `properties` plus the seven meta-properties `$identifier`/`$title`/`$blueprint`/`$team`/
  `$icon`/`$createdAt`/`$updatedAt` — never a mirror/calculation/aggregation value (those are
  computed per response and never stored, so they are unsearchable everywhere). Nothing about the
  stored Port document or the wire shapes changes: a query is a filter over instances, not a model
  feature, and Port has no equivalent.

## System blueprints (V31)

Toadie seeds exactly two system blueprints — `_team` and `_user` — flagged `system: true` on the
wire and protected against deletion and base-shape removal (v1.26.0, phase 4 of the Port
data-model move, see `.claude/docs/persistence.md` "V31"). **Base shapes**: `_team` has a single
optional self-relation `parent` (the composition hierarchy's parent link, named in the
`hierarchyRelations` map under the `"composition"` key) and no properties; `_user` has a
required `email` string property (format: email) and a many-valued optional relation
`team → _team`. ADMIN may extend both with extra properties/relations, set or change
`hierarchyRelations`, or add `ownership`; identifier rename, removal of a base property/relation,
or dropping a base `required` is rejected `400` (`validateSystemExtension`). Identifiers starting
with `_` are reserved: `POST` creating any `_*` blueprint is `400`. DELETE on a system blueprint
is `409` ("a property of the row"). The other Port system blueprints (`_scorecard`, `_rule`,
`_rule_result`, `_ai_*`, `_mcp_server`, `_workflow`) are still NOT modeled. Snapshot date
2026-09-11 — re-check <https://docs.port.io/context-lake/data-model/setup-blueprint/default-blueprints/>
when adding another system blueprint.

## Import and export (phase 6, v1.28.0)

Bulk import lands server-side for both registries: `POST /api/v1/blueprints/import` +
`/import/check` (ADMIN, `blueprints/BlueprintImport.kt`) and `POST /api/v1/entities/import` +
`/import/check` (any authenticated user, `entities/EntityImport.kt`) — the
`catalog/CatalogFileImport.kt` report-and-skip precedent, one level up. A request carries
`documents: List<JsonObject>` (up to 200) plus `replaceExisting: Boolean` (default `false`);
each document is decoded and classified INDEPENDENTLY — the strict `blueprintJson` decode
(`explicitNulls = false`, `ignoreUnknownKeys = false`) — so one malformed document is that row's
`INVALID` with a fixed message (`Document does not match the expected schema`, never the raw
kotlinx exception text), never a whole-request `400`; only a non-`JsonObject` array element (a
string, a number, `null`) fails to decode into `JsonObject` at all and IS a request-level `400`,
thrown by ContentNegotiation before either planner runs. `POST …/import/check` runs the
IDENTICAL classification against the current registry, storing nothing — the same
`{real run, dry-run} share one classification` promise `catalog/CatalogFileImport.kt` makes for
catalog files. One consequence for a MIXED batch: the two endpoints are separate requests, so an
entity dry-run resolves `blueprint` against the STORED registry only — an entity whose blueprint
is itself still pending in the same paste checks as `INVALID` "Unknown blueprint" and lands as
`CREATED` only on the real Import, after the blueprint half has stored (documented limitation;
the page sends blueprints first for exactly this reason).

**Per-row statuses** (`OntologyImportStatus`, shared by both endpoints): `CREATED` (stored as a
new row), `UPDATED` (an existing row replaced in place — only reachable with
`replaceExisting: true`; the only way an import PUTs `_team`/`_user`, `validateSystemExtension`
still applies), `EXISTS` (an existing row found with the flag off — nothing stored, `id` names
it; renders gray in the SPA, not red — nothing failed), `INVALID` (shape/registry/reference
validation failure, an unresolvable target, a registry cap, or a cycle through a MANDATORY
reference — see below), `CONFLICT` (an in-batch duplicate identifier, case-insensitive; the
later document loses), `ERROR` (an unexpected storage failure, or a pass-2 residual — see
below). The response is `200` even when every document failed.

**Ordering and deferral, one mechanism for forward references and cycles.** Both planners
topologically order the batch (Kahn's algorithm, ties broken by the document's 0-based batch
index) over the sibling references each document's OWN definition names: for a blueprint,
`relations`/`aggregationProperties` targets; for an entity, relation targets
(`entities/EntityReferences.kt#entityTargets`), `team` values (always against `_team`), and
`format: team|user` property values (`formatTargets`) — the same three sources
"Lifecycle rules" below enumerates for the rename cascade. Only edges to a sibling that is
ITSELF a batch CREATE matter — a target already active in the registry, or one being replaced
in place, needs no ordering, since it already resolves. A plain forward reference (a document
naming a sibling declared LATER in the same batch) is resolved by REORDERING alone: Kahn places
the target before its referrer, so the first write already carries the reference — nothing is
ever deferred for an acyclic batch. A genuine CYCLE stalls Kahn; at a stall the LOWEST-index
remaining document is emitted anyway, with every one of its references to a sibling not yet
placed DEFERRED — stripped from its first write (`pass1`), restored by a second full write
(`update`) once every sibling exists. For a blueprint, stripping a relation also strips any
mirror property whose path starts with it, an Inherited `ownership.path` starting with it, and
a `hierarchyRelations` map naming it (`BlueprintValidation.kt`'s own dependency rules, applied in
reverse). For an entity, a dropped SCALAR reference (a `many: false` relation, `team` string, or
scalar `format` property) removes the key entirely (the `null`-means-absent convention already
in place); a dropped ARRAY reference removes only the unresolved element(s), keeping the rest.

**The required-reference exception (entities only).** A blueprint has no field that is mandatory
at create time — every schema field, relation, and Toadie's own `hierarchyRelations` are optional
on a bare `BlueprintRequest` — so a blueprint cycle is ALWAYS storable in two writes. An entity's
`schema.required` and a relation's `required: true` are not: a cycle running through a
`required: true` relation, or a `format: team|user` property named in `schema.required`, cannot
be deferred (dropping it would immediately violate the very rule that made it mandatory) and is
`INVALID` instead — "Circular required reference '<field>' within the batch". Rejecting that
document can itself change what OTHER documents resolve against (their own reference to the
now-gone document goes missing), so the entity planner re-runs its findings/cap fixpoint after
every such rejection until a full pass changes nothing.

**The pre-flight fixpoint.** Before ordering, both planners iterate their remaining candidates
to a fixpoint: a blueprint's unresolved relation/aggregation target (checked against
`registry ∪ will-store identifiers`, `blueprintTargets`) or an entity's `entityFindings` against
`registry ∪ will-store keys` rejects the document; the registry cap (`MAX_BLUEPRINTS`,
`MAX_ENTITIES_TOTAL`/`MAX_ENTITIES_PER_BLUEPRINT`, counted over CREATE rows only, in submission
order) rejects any create past the limit. Removing a document for one reason can free a cap slot
or resolve an unknown-target rejection for another (an earlier document's own removal shrinks the
create count another document is measured against), so the iteration repeats until a full pass
rejects nothing further — this is what makes the dry-run's prediction match the real run's
eventual pass-2 outcome: both compute their verdicts against the SAME "what the batch will look
like once it all resolves" snapshot, never against submission order alone.

**The pass-2 residual.** A pass-2 write can still fail — only from a CONCURRENT change during
the batch (a sibling deleted or edited by another caller between the pre-flight fixpoint and the
second write), since the fixpoint already proved the full document resolves against the batch as
planned. That failure reports `ERROR` WITH the row's `id` (and, for an entity, its `findings`)
and a message naming what happened ("Stored without its deferred targets/references: …") —
never silently left `CREATED`/`UPDATED`, so a caller always knows the row is missing its deferred
parts and needs re-import or a manual fix.

**Reads are plain, uncoordinated snapshots.** `BlueprintService.list()` (already used by the
registry GET) and the new `EntityService.importSnapshot()` (active blueprint definitions plus
every active entity's `(blueprintId, identifier) → id`, one committed transaction, no lock) are
the ONLY reads either planner needs; see `.claude/docs/persistence.md` "Ontology import" for why
every actual write still goes through the ordinary V27/V28 lock per row regardless.

## Upstream pages snapshotted (2026-09-08)

- <https://docs.port.io/context-lake/data-model/configure-data-model/>
- <https://docs.port.io/context-lake/data-model/setup-blueprint/overview/>
- <https://docs.port.io/context-lake/data-model/setup-blueprint/properties/overview/> and its
  per-type pages (`string`, `number`, `boolean`, `array`, `object`, `enum`, `url`, `email`,
  `user`, `team`, `datetime`, `timer`, `yaml`, `markdown`, `proto`, `labeled-url-object`,
  `embedded-url`, `swagger`, `mirror-property`, `calculation-property`, `aggregation-property`,
  `meta-properties`)
- <https://docs.port.io/context-lake/data-model/setup-blueprint/relate-blueprints/>
- <https://docs.port.io/context-lake/data-model/setup-blueprint/default-blueprints/>
- <https://docs.port.io/context-lake/data-model/define-your-ontology/>
- <https://docs.port.io/context-lake/business-context/ownership/>

Added 2026-09-09 (phase 2, entities):

- <https://docs.port.io/api-reference/create-an-entity/>
- <https://api.getport.io/swagger/json> — Port's public OpenAPI document; the source of the
  entity wire shape above.
