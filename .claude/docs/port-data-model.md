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
- **Hierarchy relation (Toadie)** — an optional per-blueprint pointer at one of its own
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
deeper segments are other blueprints' relations and are not resolved.

## Calculation properties

```json
{ "calculationProperties": { "status": {
    "title": "Status", "type": "string", "calculation": ".properties.rawStatus",
    "colorized": true, "colors": { "OK": "green", "WARNING": "yellow", "CRITICAL": "red" } } } }
```

`type` ∈ the five property types; `format`/`spec` as for properties; `calculation` is a **jq**
expression over the entity (`.properties.x`, `.identifier`, `.relations.r`) — Toadie stores it
as text (1–10 000 chars, not parsed); `colors` values are the 14 colours.

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

## Ownership

`{ "ownership": { "type": "Direct", "title": "Owning team" } }` — entities MUST carry a `team`
value (string or array) naming one or more ACTIVE entities of the `_team` system blueprint
(v1.26.0, phase 4 — see "System blueprints" below). `{ "type": "Inherited", "path": "service.owningTeam" }` —
ownership is computed at read time by walking the `path` over single-valued relations to a
blueprint with Direct ownership; `path` is a dot-chain of relation identifiers (first must be
this blueprint's), and the final `team` is never stored (the column stays NULL). Absent = no
ownership constraint. **Ownership is informational only — it never gates permissions.** Any
authenticated user may read/modify any entity regardless of its ownership.

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
- Evaluation of mirror/calculation/aggregation properties — they stay definitions on the
  blueprint; entity `properties` carrying one of their ids is `COMPUTED_PROPERTY`, `400`, never
  accepted as input, and Toadie never computes their values.

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

Phase 3 (v1.25.0) adds one Toadie-only field that has no equivalent in Port's own model:

- **Hierarchy relation** — `Blueprint.hierarchyRelation: String?`, an optional identifier of ONE
  of the blueprint's own `relations` entries. The rule: it must be a KEY of `relations`, and that
  relation must have `many: false` (a hierarchy parent is singular by definition) — naming an
  unknown or `many: true` relation is `400`. It is stored BESIDE the Port document, in its own
  column (V29), never inside `definition`/`schema`/`relations` — so `toDefinition()`'s emitted
  Port JSON stays byte-identical to what phase 1 already produced, and any future Port export
  simply drops the column rather than needing to strip anything out of the document. Wire shape
  follows the blueprint convention: optional, NOT `nullable`, ABSENT when unset (never `null`);
  renaming/removing the named relation is not cross-checked (the same no-grandfathering-on-edit
  posture as the schema/relations themselves — see "Lifecycle rules" above).

  The **entity hierarchy** derives from it, purely: an entity's parent is the target of its
  OWN blueprint's `hierarchyRelation` relation value (a `many: false` relation, so at most one
  target); blueprints that set no `hierarchyRelation` are hierarchy roots, and so is any entity
  whose relation value is unset or does not resolve. The entity graph (below) flags the edges
  that carry this relation with `hierarchy: true`, so the hierarchy tree and the general
  relation graph are two views over the same data, never a separately stored parent pointer.

- **Entity graph** — `GET /api/v1/entities/graph` renders entities and their relations together
  (`entities/EntityGraph.kt`, the `catalog/Graph.kt` counterpart one level down). Each node's id
  follows the grammar `"<blueprint>|<identifier>"` (`|` appears in neither the blueprint nor the
  entity identifier charset, so exactly one `|` per id and the split is unambiguous). Unlike the
  catalog graph, there are no virtual/MISSING nodes: an edge is emitted only when BOTH its ends
  are among the entities the `blueprint`/`q` filters selected, so a relation value naming a
  filtered-out or no-longer-resolving target simply contributes no edge — never a placeholder
  node. Each node carries `findings` as a plain COUNT (not the detailed list `GET`/list return)
  of the same `entityFindings` computation — the stale marker, condensed for a graph face.

  **Ownership edges** (v1.26.0, phase 4): when an entity carries a `team` value and that node
  is shown in the graph, one edge is emitted per team value to the corresponding `_team|<id>`
  node (if also shown). The edge carries `ownership: true, hierarchy: false`. A `_team` node
  also matches a `team` filter (single value, case-insensitive), so a team-filtered graph
  retains the team nodes to close ownership relations — **Inherited entities carry no stored
  `team` and never match the team filter** (documented limitation; a renamed or deleted `_team`
  affects inherited entities' stale findings, not their graph presence).

## System blueprints (V31)

Toadie seeds exactly two system blueprints — `_team` and `_user` — flagged `system: true` on the
wire and protected against deletion and base-shape removal (v1.26.0, phase 4 of the Port
data-model move, see `.claude/docs/persistence.md` "V31"). **Base shapes**: `_team` has a single
optional self-relation `parent` (the entity hierarchy's parent link) and no properties; `_user`
has a required `email` string property (format: email) and a many-valued optional relation
`team → _team`. ADMIN may extend both with extra properties/relations, set or change
`hierarchyRelation`, or add `ownership`; identifier rename, removal of a base property/relation,
or dropping a base `required` is rejected `400` (`validateSystemExtension`). Identifiers starting
with `_` are reserved: `POST` creating any `_*` blueprint is `400`. DELETE on a system blueprint
is `409` ("a property of the row"). The other Port system blueprints (`_scorecard`, `_rule`,
`_rule_result`, `_ai_*`, `_mcp_server`, `_workflow`) are still NOT modeled. Snapshot date
2026-09-11 — re-check <https://docs.port.io/context-lake/data-model/setup-blueprint/default-blueprints/>
when adding another system blueprint.

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
