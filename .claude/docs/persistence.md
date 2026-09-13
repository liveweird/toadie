### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in `application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service (host port **5433** — Lettuce may occupy 5432 on the same machine; in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived JDBC connection. Migrations are the single source of truth for schema; do not call `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql` makes every long-lived database refuse to boot (a failure no fresh-container CI run can see). `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and a red pin means REVERT the edit (clarifications go into this doc), never update the pinned value.
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access. `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the services and publishes them into `Application.attributes` (`UserServiceKey`, `GraphLayoutServiceKey`, `EntityGraphLayoutServiceKey`, `TokenBlocklistServiceKey`, `CatalogFileServiceKey`, `CatalogFileEventServiceKey`, `DictionaryServiceKey`, `LabelServiceKey`, `AnnotationKeyServiceKey`, `TagCategoryServiceKey`, `EntityTypesServiceKey`, `LensServiceKey`, `BlueprintServiceKey`, `EntityServiceKey`); each service itself lives next to the feature it serves (`users/UserService.kt`, `users/GraphLayoutService.kt` — the second `EntityGraphLayoutServiceKey` instance is the SAME class over a second table, see V30 below —, `auth/TokenBlocklistService.kt`, `catalog/CatalogFileService.kt`, `catalog/CatalogFileEventService.kt`, `dictionaries/DictionaryService.kt`, `labels/LabelService.kt`, `annotations/AnnotationKeyService.kt`, `tags/TagCategoryService.kt`, `types/EntityTypesService.kt`, `lenses/LensService.kt`, `blueprints/BlueprintService.kt`, `entities/EntityService.kt`). The Exposed table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently wrap them); one wrinkle: V1 created `users.id` as `BIGSERIAL` (64-bit in SQL, 32-bit everywhere above it) while V5 uses `SERIAL` — harmless at this scale, documented so nobody "fixes" one to match the other without a migration.

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries go through R2DBC.

**Cross-feature table reads (the service-layer rule, inherited from Lettuce).** A feature service MAY query another feature's Exposed table objects directly when the read must run **inside its own transaction** (SQL joins, atomic snapshots) — calling the other feature's *service* would open a second transaction and break atomicity. Route handlers never touch tables (services only). The reads in place: `BlueprintService`'s read of the active `HIERARCHY` rows in `DictionaryService.Entries` (every `hierarchyRelations` key must be one, inside the V27 locked write) and `DictionaryService.replace`'s read of `BlueprintService.Blueprints` (the hierarchy referrer check, under the same lock — V34), `CatalogFileService.joined()`, `LensService.joined()`, and `EntityService.joined()` (catalog list/read, the lens list, and the entity list/read join `UserService.Users` for the creator's display fields), `EntityService.loadSnapshot()` — the per-call read of active rows from `BlueprintService.Blueprints`, the `_team`/`_user` system blueprints (if they exist), and the set of blueprints named by any Inherited `ownership.path` (each entry decoded once inside the transaction), used to validate relation targets and compute inherited teams. The definition each entity's `entityFindings` is checked against, and every map (`(blueprintId, identifier)` pairs for relations, `(blueprintId, teamId)` for ownership, and the blueprint-graph for path-following) are read inside the calling write/list/read transaction so an entity is never validated against definitions mid-change — see the V28 lock protocol below, `CatalogFileService.resolvedNamespace()` (every catalog write resolves its namespace against the active `NAMESPACE` dictionary entries inside the write's own transaction: blank → the ADMIN-flagged default entry, none flagged → 400; a concrete value must be an active entry — STRICT, no grandfathering: a stored file whose namespace was since removed cannot be saved until it is re-added or changed. The stored row AND content JSON always carry the resolved concrete value), and `CatalogFileService.loadRegistrySnapshot()` (one snapshot of the five soft-check registries — active labels with kinds+closed value lists, annotation keys with kinds, tag categories, per-kind `entity_types` dictionaries, and the GLOBAL `LIFECYCLE` dictionary entries — plus the active `NAMESPACE` dictionary values (feeding ONLY the Errors report's report-only namespace check, never the soft checks) — read inside the calling write/report transaction; the soft rules themselves are the PURE `registryFindings` in `catalog/Errors.kt`: label key registered + kind allowed + value in the closed list, annotation KEY registered + kind allowed with values staying free, tag registered in a category whose kinds allow the file's kind, non-blank `spec.type` in the kind's active dictionary — no dictionary allows no types — and non-blank `spec.lifecycle` an active entry, byte-exact against the lowercase-folded stored values; empty registries allow nothing). These are the write path's SOFT checks (`CatalogFileService.softFindings` adds reference resolution): strict by default with the same no-grandfathering rule — a stored file whose registry row was since removed cannot be strict-saved until fixed — but waivable per write via `allowInvalid=true` (see `.claude/docs/authorization.md`), and the same snapshot feeds the Errors report (`GET …/errors` — which adds the report-only `STRUCTURE_INVALID`/`NAMESPACE_NOT_ALLOWED` checks over stored content, rules that stay HARD on writes) and `POST …/check`.

**Tag-category ownership under concurrency.** `TagCategoryService` takes a transaction-scoped
PostgreSQL `SHARE ROW EXCLUSIVE` lock on `tag_categories` before the first registry read in
create/replace/delete. The lock serializes these small, infrequent admin writes across service
instances, even when the registry is empty, while ordinary catalog/registry reads continue.
The ownership scan, active-category capacity check, target-existence check, and mutation stay
in the same read-committed transaction: after a lock wait, checks see the preceding commit.
Acquire the lock before row locks or reads that influence a write; locking only current rows
would miss new categories. Commit/rollback releases it. The JSON storage and soft-delete
model are unchanged, and conflicts retain the existing `409` response. This is a cooperating
writer protocol, not a unique constraint over array members: direct SQL writers and old
application versions must not bypass it. Deploy all tag writers with this protocol before
relying on the guarantee. Existing duplicate ownership is not silently repaired. Concurrent
full replacements of one category still use last-write-wins semantics.

The current R2DBC path may finish cancellation only after a blocking database lock is
released. A cancelled write then rolls back; this protocol does not add a lock-wait deadline
or promise immediate database-query cancellation.

**Entity graph reads.** `EntityService.graph(EntityGraphFilter)` (Phase 3, v1.25.0) is a plain
read transaction, not a write path: it loads ONLY the rows the `blueprint`/`q` filters show
(the catalog graph's rule, one level down — an edge needs BOTH ends shown, so a row the filter
hid contributes neither a node nor an edge, never a virtual/MISSING one), joins the active
blueprint snapshot for titles/`hierarchyRelations`, and computes each returned row's `findings`
with the SAME `entityFindings` the list/read endpoints use, condensed to a count. No lock is
taken — a plain committed read, like the entity list.

**Entity query reads (phase 7, v2.0.0).** With a `query` (`.claude/docs/entity-query-language.md`)
`EntityService.graph` runs TWO short read transactions with the CPU work between and after them:
the first (`loadQuerySchema`) reads the active blueprints plus the active `HIERARCHY` dictionary
values from `DictionaryService.Entries` — a sanctioned cross-feature table read, the
`BlueprintService` precedent — and closes; the query is parsed and validated against that
committed schema OUTSIDE any transaction (a refused query never reads an entity row, and the
Levenshtein suggestion scan never holds a pooled connection); the second is today's graph read
plus `loadWorkspaceSnapshot` — every active row of every active blueprint, the `SnapshotRow` shape
of `loadSnapshot` with NO `blueprintId IN (…)` narrowing and NO decode yet — rather than the
shown blueprints' targets only, since a traversal may pass through entities the
`blueprint`/`q`/`team` filters hide. After it closes, the rows are decoded, `InMemoryQueryGraph`
is built and `InMemoryQueryExecutor` runs on the dedicated `entity-query` pool under the
cooperative `QueryBudget` (the phase-5 rule: a wide join never pins a pooled R2DBC connection or
the entity lock); the shown rows are then intersected with the returned `(blueprint,
identifier)` set and handed to the unchanged `buildEntityGraph`. The two reads are independent
committed snapshots: a blueprint changed between them is validated against the first and
evaluated against the second, which the evaluator tolerates (an unknown label or relation simply
matches nothing). Both are plain reads, no lock; in-flight evaluations are bounded by
`MAX_CONCURRENT_ENTITY_QUERIES` permits taken after validation, before the second read (`429`
when none is free; a refused query costs no permit);
no snapshot cache (the trigger is documented in the language reference). `checkQuery` is the
first transaction alone, validation after it, no entity rows at all.

**Blueprint targets under concurrency (V27).** `BlueprintService` takes the same
transaction-scoped `SHARE ROW EXCLUSIVE` lock on `blueprints` before the first read in
create/update/delete: every relation/aggregation `target` inside a definition is a byte-exact
blueprint identifier held in JSON, not a foreign key, so the "target exists", "rename cascades
into the referrers" and "a targeted blueprint cannot be deleted" rules are cooperating-writer
rules over ≤200 rows loaded once under the lock. Since V34 `DictionaryService.replace` takes the
SAME `SHARE ROW EXCLUSIVE` lock on `blueprints` — for the `HIERARCHY` dictionary only — before its
referrer check (a value being removed or renamed must not be named by any active blueprint's
`hierarchy_relations`, else 409 naming the referrers), so a concurrent blueprint write waits and
then validates its keys against the COMMITTED dictionary; blueprint writers read
`DictionaryService.Entries` under their own lock for the same reason (`DictionaryConcurrencyTest`
pins the race). `infra/db/Locking.kt`'s `lockingTransaction`
is the shared helper behind this lock (and `TagCategoryService`'s and `EntityService`'s below) —
one `LOCK TABLE …` per statement, in the order given, inside a READ COMMITTED transaction. The
same caveats apply verbatim: direct SQL writers bypass it, readers never wait, cancellation may
land only after the lock releases.

**Entity targets under concurrency (V28).** Entity create/update/delete run under a TWO-table
lock protocol, in one fixed global order (deadlock-free): `LOCK TABLE blueprints IN SHARE MODE`
first, then `LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE`, both inside the write's ordinary
READ COMMITTED transaction. `SHARE` is self-compatible, so entity writers only serialize against
EACH OTHER on `entities` (via the `SHARE ROW EXCLUSIVE` half) — but `SHARE` on `blueprints`
conflicts with the blueprint writers' `SHARE ROW EXCLUSIVE` (the V27 lock above), so an entity is
never validated against a blueprint definition mid-change, nor attached to (or left orphaned by)
a blueprint being deleted. `BlueprintService.delete` needs no second lock: holding its own `SHARE
ROW EXCLUSIVE` already excludes every entity writer, so it counts `entities WHERE blueprint_id =
? AND NOT marked_as_deleted` — a sanctioned cross-feature table read of `EntityService.Entities`
— before its existing referrer check, and throws `409` naming the active entity count. Blueprint
schema/relation PUTs need nothing new: entities are not re-validated at edit time, they go stale
(re-checked, and re-blocked on their own next save) — see `.claude/docs/port-data-model.md`
"Entities" → "Lifecycle rules". Entity/`_team` rename cascades (v1.26.0, phase 4) and referrer
checks for all three target sources — relations, `team` field, and `format: team|user` properties
— run under the same lock protocol; the three sources are checked in one referrer sweep on
delete. The `_team` delete-vs-entity-create race is pinned in `EntityConcurrencyTest`. The same
caveats as V27 apply: direct SQL writers bypass the protocol, readers never wait, and cancellation
may land only after a held lock releases.

The lock-mode compatibility and transaction lifetime follow the
[PostgreSQL explicit-locking rules](https://www.postgresql.org/docs/18/explicit-locking.html).

**Ownership storage rule (v1.26.0, phase 4).** An entity's `entities.team` column is NULL when
the blueprint has `ownership: { type: Inherited }` (the computed `team` is never stored) and
MUST carry a value when Direct/absent (the stored `team` field). The rule is enforced during
entity write validation. This storage rule is unchanged by the v1.30.0 read-side `team` filter:
`entities/EntityFilter.kt`'s `inheritedTeamMatches` (an extension on `EntityService`) resolves
Inherited teams in memory over the SAME snapshot machinery every other read builds, reached
through `EntityService.rowLookupFor` — the one narrow internal accessor into the otherwise-private
`loadSnapshot`/`EntitySnapshot` — costing one extra bounded query per team-filtered request over
the in-scope Inherited blueprints' entities, and none at all when no Inherited blueprint is in
scope.

**Computed-property evaluation and the widened snapshot (phase 5, v1.27.0).**
`EntityService.loadSnapshot(definitions, blueprintsByIdentifier, computed)` widens the same
per-call snapshot read above with every blueprint a mirror path, an aggregation `target`, or a
`pathFilter` chain (in either direction) might touch, plus the Inherited ownership path
blueprints of each of THOSE — `entities/EntityComputed.kt`'s `computedPathBlueprints`, the
static twin of `ownershipPathBlueprints` — so mirror/aggregation evaluation never issues a fresh
query mid-walk. The widening runs only when `computed = true`: `list`, `read`, and `create`
request it (their responses evaluate computed properties); `update` (a `204`, no body) and
`graph` (nodes carry no `properties` at all) pass `computed = false` and never pay for it — the
`SnapshotRow.decoded`/`EntitySnapshot.inbound` machinery below is built or not per call, not
per feature. Each snapshot row decodes its document/team JSON ONCE regardless of how many
computed properties or findings ask for it (`SnapshotRow.decoded`, `by lazy` — before phase 5
`rowLookup` re-decoded on every call), and the reverse-lookup index aggregation candidates need
(`EntitySnapshot.inbound`, `EntityIndex.inbound`, keyed by `(target blueprint, relation value)`
over every snapshot row's relations) is itself `by lazy` — built only the first time a computed
property actually asks "who points at this entity", so an `update`/`graph` snapshot never
constructs it at all.

Evaluation itself runs OUTSIDE the transaction that built the snapshot: `list`/`read`/`create`
materialize their rows (as an in-memory `RawEntity`) plus the `EntitySnapshot` INSIDE
`suspendTransaction`/`writeTransaction` exactly as before, then map them through
`EntityService.toResponse` — a plain function over that in-memory data, no query inside it —
AFTER the transaction closes. A pathological jq expression or a wide aggregation fan-out then
pins a request-handling coroutine, never a pooled R2DBC connection or a database lock (see
`.claude/docs/security.md` "Computed-property evaluation (jq)" for why that boundary is load-
bearing). `update` and `graph` never evaluate computed properties at all, so this ordering
concern does not apply to them. Since v1.29.0 `EntityService.toResponse` is itself a `suspend`
function: each calculation is submitted to the bounded `entity-jq` worker pool
(`.claude/docs/security.md`) and awaited on the request-handling coroutine, still entirely
after the transaction above has closed — the transaction boundary itself is unchanged.

**Ontology import (phase 6, v1.28.0).** `blueprints/BlueprintImport.kt`'s and
`entities/EntityImport.kt`'s effectful `import`/`importCheck` extensions add NO new locking
protocol: each row's pass-1 and pass-2 write is an ordinary call into
`BlueprintService.create`/`update` or `EntityService.create`/`update`, so it runs under that
service's own V27 (`blueprints`) or V28 (`blueprints` then `entities`) table lock exactly as a
single-document API call would — a 200-document batch takes the lock up to 400 times (pass 1 +
pass 2 per deferred row), never once for the whole batch. The registry snapshot each planner
reads before writing (`BlueprintService.list()`, the new `EntityService.importSnapshot()` — one
plain, lock-free transaction reading active blueprint definitions plus every active entity's
`(blueprintId, identifier) → id`) is a single committed read, the same posture as the catalog
import's `batchIdentities`/`withImportSnapshot` and the entity graph's plain read: it can go
stale the instant a concurrent writer commits, which is exactly why a pass-2 failure is possible
and is reported `ERROR` naming the row's `id` rather than silently left `CREATED`/`UPDATED` — a
concurrent-change residual, not a bug in the ordering. `planBlueprintImport`/`planEntityImport`
themselves touch no table at all: pure functions over the snapshot and the batch, so the two
planner test files run without Docker.

Current migrations are `V1`–`V34` — small enough that this section is the catalog (Lettuce splits it into `.claude/docs/features/migrations.md`; introduce that file when the count warrants it):

- `V1__init` — the `users` table: `name` (≤50), `email` (≤254), `password_hash`, `role` with `CHECK ("role" IN ('ADMIN', 'USER'))` (single-column role storage; the wire shape stays a `roles` set, see `.claude/docs/authorization.md`), `password_changed_at` (epoch millis, 0 = never — retained as a timestamp; V25's monotonic `auth_version` supersedes timestamp-based token invalidation), `marked_as_deleted`; plus the partial unique index `uq_users_email_active` over active rows.
- `V2__create_revoked_tokens` — the JWT blocklist for `/logout`: `jti` PK + `expires_at`, with an index on `expires_at` (the revoke path prunes expired rows opportunistically, so the table stays tiny).
- `V3__seed_admin` — the bootstrap administrator `admin@toadie.local` / `changeme`, idempotent via `ON CONFLICT DO NOTHING`; production neutralizes it at startup (see "Default admin" in `.claude/docs/security.md`).
- `V4__enable_unaccent_extension` — Lettuce's unaccent migration, backing every `containsNormalized` substring filter (see `infra/db/Sql.kt`).
- `V5__create_catalog_files` — stored catalog-info.yaml documents (one row = one entity; structured JSON in `content`, identity columns `kind`/`name`/`namespace` denormalized), `created_by` FK to `users`, epoch-millis `created_at`/`updated_at`, and the partial unique index `uq_catalog_files_entity_active` over `(kind, namespace, LOWER(name))` — Backstage's case-insensitive identity, active rows only.
- `V6__widen_catalog_kinds` — the kind CHECK grows from Component-only to the seven landscape kinds (API, System, Domain, Resource, Group, User join; Location/Template deliberately out).
- `V7__create_dictionary_entries` — Lettuce's shared dictionaries table (single-valued): enum-name discriminator `dictionary` (the Kotlin `Dictionary` enum is the whitelist — no CHECK), `position` (rewritten from payload order on every save; soft-deleted rows keep stale positions — reads filter active and order by `position, id`), `value` ≤63 (stored lowercase-folded), soft-delete, plus the partial unique index `uq_dictionary_entries_value_active` over `(dictionary, value)` active rows.
- `V8__seed_namespaces` — seeds the `NAMESPACE` dictionary with Backstage's `default` namespace (idempotent, `ON CONFLICT … DO NOTHING` against V7's partial index). An ordinary entry — admins may reorder or remove it like any other.
- `V9__default_namespace_flag` — `is_default` on `dictionary_entries` (flagging the active seeded `default`), plus the at-most-one backstop `uq_dictionary_entries_default_active` over `(dictionary)` active flagged rows; the EXACTLY-one rule for non-empty documents lives in `validateDictionaryUpdate`, and `DictionaryService.replace` clears every active flag before its upserts so moving the flag in one save never trips the index. Blank/omitted catalog-file namespaces resolve to the flagged entry at write time.
- `V10__create_labels` — the ADMIN-curated label registry: one row = one allowed `metadata.labels` key with its closed value list and applicable kinds (both JSON arrays in TEXT — the `catalog_files.content` precedent, no child tables), soft-delete, plus the partial unique index `uq_labels_key_active` over `LOWER(key)` active rows (no case-twin keys; a soft-deleted label frees its key). No seed — an empty registry means no file may carry labels until an admin defines some (`V22` later seeds the reference set).
- `V11__create_tag_categories` — the ADMIN-curated tag categories (an INTERNAL Toadie concept): one row = one category with its display `name` (≤63, not Backstage grammar), tag list, and applicable kinds (JSON arrays in TEXT), soft-delete, plus the partial unique index `uq_tag_categories_name_active` over `LOWER(name)` active rows. **The one-category-per-tag invariant is enforced service-side in the serialized write transaction** (tags remain JSON arrays; the locking protocol is described above). No seed — an empty registry means no file may carry tags (`V22` later seeds the reference set).

- `V12__user_disabled_features` — Lettuce's per-user feature flags (the DISABLED set — no row = enabled, so the empty table needs no backfill): `(user_id, feature)` PK, `ON DELETE CASCADE`, no CHECK on feature (the Kotlin `Feature` enum is the whitelist), plus the feature index behind the users-list `feature`/`featureEnabled` filter pair.

- `V13__seed_mfa_disabled_flags` — MFA joins the flags with an INVERTED default (opt-in): every pre-existing user gets the `MFA` disabled row (`ON CONFLICT DO NOTHING`); `UserService.create` inserts the same row for every later user.

- `V14__create_entity_types` — the ADMIN-curated per-kind type dictionaries (an INTERNAL Toadie constraint on the open `spec.type` field): one row = one type-bearing kind (canonical casing, no CHECK — the Kotlin `TYPE_BEARING_KINDS` list is the whitelist; User excluded, its spec has no type) with its allowed types (JSON array in TEXT), soft-delete, plus the partial unique index `uq_entity_types_kind_active` over `(kind)` active rows (no LOWER — kinds are stored canonical). The dictionaries are INDEPENDENT: no cross-row uniqueness (unlike tags' one-category-per-tag rule).

- `V15__seed_entity_types` — seeds all six dictionaries with the descriptor reference's well-known values (Component service/website/library, API openapi/asyncapi/graphql/grpc, System product/service/feature-set, Domain product-area/product-group/bundle, Resource database/s3-bucket/kubernetes-cluster, Group team/business-unit/product-area/root), idempotent against V14's partial index (the V8 idiom). Ordinary rows — admins may edit or delete them; a kind left without a dictionary allows NO types (required-type kinds then cannot save).

- `V16__seed_lifecycles` — seeds the `LIFECYCLE` dictionary (V7's table, second `Dictionary` enum value) with the well-known values experimental/production/deprecated (positions 0–2, the V8 conflict idiom). NO default flag — `Dictionary.usesDefault` is false for LIFECYCLE, and `validateDictionaryUpdate` rejects flagged items on it (the per-dictionary branch). The GLOBAL allowlist every catalog write's non-blank `spec.lifecycle` must be in.

- `V17__create_annotation_keys` — the ADMIN-curated annotation-key registry (the V10 labels shape minus `allowed_values` — annotation VALUES stay free): one row = one allowed `metadata.annotations` KEY with its applicable kinds (JSON array in TEXT), soft-delete, plus the partial unique index `uq_annotation_keys_key_active` over `LOWER(key)` active rows. No seed — an empty registry means no file may carry annotations until an admin registers keys (the labels posture; `V22` later seeds the reference set). The server-written `backstage.io/*` keys cannot be registered.

- `V18__add_users_language` — the per-user language (Lettuce's V61): `users.language VARCHAR(10) NOT NULL DEFAULT 'en'` — no index, no CHECK (`SUPPORTED_LANGUAGES` in `dictionaries/Languages.kt` is the whitelist, the V12 idiom). Drives the UI at sign-in and every server-composed email's language; set at create, changed only via `PUT /users/{id}/language` (see `.claude/docs/authorization.md`).

- `V19__create_graph_layouts` — the per-user Graph-page layout (`graph_layouts`, one row per user, `user_id` PK/FK `ON DELETE CASCADE`): `mode VARCHAR(10)` (`auto`/`manual` — no CHECK, `GRAPH_LAYOUT_MODES` in `users/GraphLayout.kt` is the whitelist) + the manually dragged node positions as ONE JSON object in TEXT keyed by node id `kind:namespace/name` (the `catalog_files.content` precedent) + `updated_at` (+ the collapsed node ids since `V24`, below). A **hard-delete table** (the `user_disabled_features` exception class): a pure per-user settings row whose PUT is a wholesale replace — no history worth keeping. Read/replaced only via GET/PUT `/users/{id}/graph-layout` (`users/GraphLayoutService.kt`, upsert — a drag stop and a mode switch may race from one client). `V30` gives the Entity graph page an independent twin table, `entity_graph_layouts`, behind its own `/users/{id}/entity-graph-layout` pair (below).

- `V20__create_lenses` — saved filter sets (`lenses`, the labels CRUD shape + V5's creator column): `name` (≤100), `visibility VARCHAR(10)` (`PRIVATE`/`PUBLIC` — no CHECK, the Kotlin `LensVisibility` enum is the whitelist, the V12/V18 idiom), the nine shared filter slots as ONE JSON object in TEXT (`filters` — the `catalog_files.content` precedent), `created_by` FK (`ON DELETE RESTRICT`, the V5 shape), epoch-millis timestamps, soft-delete, plus the partial unique index `uq_lenses_owner_name_active` over `(created_by, LOWER(name))` active rows — names are unique PER OWNER only (public lenses from different creators may share one; the picker disambiguates by creator name). PRIVATE rows are visible only to their creator; PUBLIC rows to everyone, both creator-only mutable (see `.claude/docs/authorization.md`).

- `V21__catalog_file_source` — source references & repo sync: three envelope columns on `catalog_files` — `source_url VARCHAR(2048) NULL` (the https URL of the file's canonical repo copy; NULL = none, the report-only `SOURCE_MISSING` Errors finding), `last_synced_at BIGINT NOT NULL DEFAULT 0` (epoch millis of the last repo→DB sync; 0 = never — the `password_changed_at` idiom, and the sortable `lastSyncedAt` field: 0 sorts most-stale-first), and `synced_content TEXT NULL` (the document JSON snapshot at sync time — the baseline attributing later changes to a side). The stored `content` stays a PURE Backstage document — the reference never enters it, the export, or the import parser. Invariants live in `CatalogFileService`: a sync (and a fetch-from-URL import row) stamps `updated_at = last_synced_at` and `synced_content` = the stored JSON, so — while `last_synced_at > 0` — `updated_at > last_synced_at` means "modified in the DB since the sync" (a changed/cleared reference resets `last_synced_at` to 0, where the bare predicate carries no drift meaning); an update bumps `updated_at` ONLY on a content change, and a changed/cleared `source_url` resets the sync state.

- `V22__seed_registries` — seeds all six ADMIN-curated registries with the reference workspace's curation, so a freshly built environment starts with the vocabulary an admin would otherwise re-enter by hand: the `external` namespace joins V8's `default` (unflagged — the default stays where V9 put it), `sunsetting` joins V16's lifecycles with the positions rewritten to the real progression experimental → production → sunsetting → deprecated, every type-bearing kind's `entity_types` list is replaced with the curated one (V15's well-known values were never this workspace's), and `labels` (8 rows), `tag_categories` (4) and `annotation_keys` (4) get their FIRST seed — V10/V11/V17 deliberately shipped none, so those registries were empty and no file could carry a label, tag or annotation at all. Every statement is an idempotent upsert whose conflict target names the table's partial unique index with that index's own predicate spelling (plus the `LOWER(...)` expression for the three case-insensitive ones); the three registries V8/V15/V16 already seeded take `DO UPDATE` — a seed declares the intended STATE and those rows exist, so the `VALUES` list IS the curation — while the three that had no seed at all take `DO NOTHING`, keeping an admin's pre-upgrade curation. Surviving an upgrade: an admin's own extra ROWS everywhere, and every edit to labels/tag categories/annotation keys. Not surviving: a re-ordering of the seeded namespaces/lifecycles, and — the one with teeth — extra TYPES added inside a seeded kind's row, since that list is replaced wholesale (files carrying such a type go strict-invalid on their next save, exactly as if an admin had removed the value by hand). One more caveat with teeth: **an admin's soft-DELETION of a seeded row does not survive either** — every conflict target is the partial active-rows index, so a soft-deleted twin does not conflict and the INSERT half creates a fresh ACTIVE row (V9's never-resurrect rule holds for dictionary saves, not for this seed); a deliberately removed seeded value comes back selectable after the upgrade, with no audit event. Accepted for a one-time seed — but the next seed migration should decide this consciously (a `NOT EXISTS` over ALL rows keeps deletions). Ordinary rows throughout — no special-case protection, the V15/V16 posture. Constraints the SQL respects because no index backs them: the seeded tag lists are DISJOINT (one-category-per-tag is service-side only) and no server-written `backstage.io/*` key is registered.

- `V23__create_catalog_file_events` — the FIRST `EventLogTable` clone (Lettuce's `V15` shape in Toadie's dialect: `SERIAL`/`INTEGER` like V20, not `BIGSERIAL`): `catalog_file_events`, the immutable per-file change trail behind the editor's History section — `catalog_file_id` (CASCADE), `user_id` (RESTRICT, so an actor is never hard-removed out from under the history), epoch-millis `created_at`, `event_type VARCHAR(40)` (no CHECK — the Kotlin `CatalogFileEventType` enum is the whitelist, the V12/V18/V20 idiom), and `params TEXT` holding the structured JSON map the SPA localizes (an UPDATED/SYNCED event's field-level diff: `changed` plus the per-field `<path>.from`/`.to`/`.added`/`.removed` companions — never free text), plus the owner-FK index. A **hard-delete table** whose CASCADE is vestigial: `catalog_files` soft-deletes, so a file's events outlive it and the DELETED event lands in a history the API can no longer reach (deliberate — kept for the record).

- `V24__graph_layout_collapsed` — the Graph's collapsed nodes join V19's per-user layout document: `graph_layouts.collapsed TEXT NOT NULL DEFAULT '[]'`, the node ids the user folded as ONE JSON array in TEXT (the `positions` column's own idiom, the same `kind:namespace/name` keys). Existing rows fold nothing. Like positions it is replaced wholesale on every save and never pruned server-side — an id of a node outside the user's current filter simply waits — and held to the same ceiling and key grammar (`validateGraphLayout`, one `requireNodeKey` for both).

**V26 — password reset grants.** `password_reset_tokens` stores a SHA-256 digest primary key,
`user_id`, captured `auth_version`, and epoch-millisecond `expires_at`, with user/expiry indexes.
Raw 256-bit tokens exist only in delivery/confirmation memory. These are ephemeral credentials,
not business/history records: consumed grants and siblings hard-delete, expired grants prune
on issuance, and failed delivery revokes its grant. Issuance/confirmation lock the active user
before touching grants; expiry cleanup runs separately to preserve lock ordering. Confirmation
uses `users.updatePasswordInTransaction` inside the SAME transaction as consumption — never
compose two independent service transactions. Concurrent same/sibling confirmations have one
winner; epoch checks also invalidate links after email/role/password changes or deletion.
Migration checksums, including V26, are pinned in `MigrationChecksumTest`.

**V27 — blueprints.** `blueprints` (Port.io-style user-definable entity kinds, v1.23.0):
identity columns denormalized for listing and uniqueness — `identifier` (≤100, the Port charset)
, `title` (≤100), nullable `description` (≤2000) and `icon` (a Port icon NAME) — plus ONE
`definition` TEXT holding the rest of the Port document (`schema`, `relations`,
`mirrorProperties`, `calculationProperties`, `aggregationProperties`, `ownership`) as JSON
encoded with `explicitNulls = false` (the `catalog_files.content`/`lenses.filters` precedent,
replaced whole on every save), `created_by` FK (`ON DELETE RESTRICT`, the V5/V20 shape),
epoch-millis timestamps, soft-delete, the partial unique index `uq_blueprints_identifier_active`
over `LOWER(identifier)` active rows (no case twins; a soft-deleted blueprint frees its
identifier — its `UNIQUE_CONSTRAINT_DETAILS` entry names the clash), and the created_by /
marked_as_deleted indexes. Relation and aggregation targets inside definitions are byte-exact
identifiers cascaded on rename by the service under the table lock (above). No content seed —
the two Port SYSTEM blueprints `_team`/`_user` arrive with V31 (below), as schema.
Migration checksums, including V27, are pinned in `MigrationChecksumTest`.

**V28 — entities.** `entities` (instances of a blueprint, Phase 2 of the Port data-model move,
v1.24.0 — see `.claude/docs/port-data-model.md` "Entities"): `blueprint_id INTEGER NOT NULL
REFERENCES blueprints(id) ON DELETE RESTRICT` — a FOREIGN KEY to the blueprint's `id`, not its
identifier, so a blueprint RENAME never touches entities (relation targets stay byte-exact
identifiers inside `document`, the blueprint-target precedent); identity columns denormalized
for listing — `identifier` (≤200, Port's wider entity charset), `title` (≤200), nullable `icon`
(≤100) — plus `team TEXT NULL` holding the `team` value EXACTLY as sent (`"x"` or `["x","y"]`;
`NULL` = absent) and ONE `document TEXT NOT NULL` holding `{properties, relations}` via
`blueprintJson` (the same explicit-nulls-off encoding as `blueprints.definition`, replaced whole
on every save), `created_by` FK (`ON DELETE RESTRICT`, the V5/V20/V27 shape), epoch-millis
timestamps, soft-delete, the partial unique index `uq_entities_blueprint_identifier_active` over
`(blueprint_id, LOWER(identifier))` active rows (no case twins WITHIN one blueprint; the same
identifier is reusable across different blueprints since the index is scoped by `blueprint_id`;
a soft-deleted entity frees its identifier — its `UNIQUE_CONSTRAINT_DETAILS` entry names the
clash), and the `blueprint_id` / `created_by` / `marked_as_deleted` indexes. Relation targets
inside `document` are byte-exact `(blueprintIdentifier, entityIdentifier)` pairs cascaded on
identifier rename by the service under the two-table lock (above). No seed — the registry starts
EMPTY, like blueprints. Migration checksums, including V28, are pinned in
`MigrationChecksumTest`.

**V29 — hierarchy relation.** `ALTER TABLE blueprints ADD COLUMN hierarchy_relation
VARCHAR(100) NULL` (Phase 3 of the Port data-model move, v1.25.0 — see
`.claude/docs/port-data-model.md` "Toadie extensions"): an identity column beside
`definition`, so the stored Port document (`toDefinition()`'s output) stays byte-identical —
a future Port export drops the column rather than stripping anything out of the JSON. No
index, no CHECK: `BlueprintValidation.kt` enforces that a non-null value names a KEY of the
same row's `relations` with `many == false`; no cascade on relation rename, since the column
names a relation KEY of its OWN row, not another blueprint's identity. Read/written alongside
`definition` in `BlueprintService.insertRow`/`update`/`toResponse`, under the same V27 table
lock (a relation the value points at may be renamed/removed in the very definition being
saved). **Replaced by V34 (v1.32.0, phase 3 enhancement)**: this single-string column is
migrated to a JSON map supporting multiple hierarchies; see V34 below. Migration checksums,
including V29, are pinned in `MigrationChecksumTest`.

**V30 — the Entity graph's own layout.** `CREATE TABLE entity_graph_layouts`, a byte-copy of
V19+V24's `graph_layouts` shape (`user_id PK/FK ON DELETE CASCADE`, `mode`, `positions TEXT`,
`collapsed TEXT`, `updated_at`) behind the Entity graph page's own
GET/PUT `/api/v1/users/{id}/entity-graph-layout`. It is a SEPARATE table, not a shared row
with `graph_layouts`: the Backstage Graph page and the Entity graph page persist independent
documents, keyed by a different node-id grammar (`kind:namespace/name` vs.
`<blueprint>|<identifier>`), so one page's manual layout/fold state never leaks into the
other's. `users/GraphLayoutService.kt` is generalized to `GraphLayoutService(database, table:
GraphLayoutTable)` over an `abstract class GraphLayoutTable(name)`, with `GraphLayouts` and
`EntityGraphLayouts` as its two concrete Exposed table objects — `Database.kt` constructs and
publishes two independent service instances, one per table, both under the
`GraphLayoutServiceKey`/`EntityGraphLayoutServiceKey` pair. A **hard-delete table**, the same
exception class as V19 (`graph_layouts`) and `user_disabled_features`: a pure per-user
settings row whose PUT is a wholesale replace, no history worth keeping. Migration checksums,
including V30, are pinned in `MigrationChecksumTest`.

**V31 — system blueprints.** `ALTER TABLE blueprints ADD COLUMN is_system BOOLEAN NOT NULL
DEFAULT FALSE` (Phase 4 of the Port data-model move, v1.26.0 — see `.claude/docs/port-data-model.md`
"System blueprints"): a flag marking Port's seeded SCHEMA blueprints `_team` and `_user`. The two
rows are inserted idempotent via `ON CONFLICT (LOWER(identifier)) WHERE NOT marked_as_deleted DO
UPDATE SET is_system = TRUE`, adopting a pre-existing user-made `_team`/`_user` by flag only (the
definition is untouched; its next PUT must then include the base shape). **This is the ONE
documented exception to "no migration seeds blueprints"**: these two rows are SCHEMA (the V22
registry-seed posture), not workspace content; they count toward `MAX_BLUEPRINTS`. `created_by`
is `(SELECT MIN(id) FROM users)` — the V3 seed admin is the first-ever `users` row and users never
hard-delete, so it survives a renamed seed email (a subselect by email would return NULL and fail
the NOT NULL FK, refusing boot). `definition` is stored in `blueprintJson`'s canonical form
(schema/relations/mirrorProperties/calculationProperties/aggregationProperties in that order;
unset optionals ABSENT, never null) so a later service write is byte-identical — `_team`'s base
is `{schema: {}, relations: {parent → _team, single, optional}, hierarchyRelations: {"composition": "parent"}}`
and `_user`'s is `{schema: {email → string required}, relations: {team → _team, many, optional},
no hierarchyRelations}`. Protections live in `BlueprintService` under the V27 lock: delete is `409`
("a system blueprint"), identifier immutable and base shape not removable/reshapable (the
`validateSystemExtension` function, `400`), identifiers starting with `_` reserved on create
(`400`); ADMIN may extend (add properties/relations, set `hierarchyRelations`, add `ownership`).
Migration checksums, including V31, are pinned in `MigrationChecksumTest`.

**V32 — Languages gains kotlin.** A pure DATA adjustment to V22's seeded "Languages" tag
category (`tag_categories.tags`, one JSON array in TEXT — see V11 above): appends `"kotlin"`
via `tags::jsonb || '["kotlin"]'::jsonb`, never a wholesale replace, so an admin's own
additions/removals to that list otherwise survive. This is the conscious decision V22's own
paragraph asked the next seed migration to make, taken in the OPPOSITE direction of V22's
INSERT half: the predicate requires an ACTIVE `Languages` row (`NOT marked_as_deleted`), so an
admin's soft-deletion of the category is **not** undone and the row is **not** re-created; and
it skips entirely if any OTHER active category already holds `kotlin` (`NOT EXISTS … o.tags::jsonb
? 'kotlin'`), respecting an admin who moved the tag elsewhere under the one-category-per-tag
invariant (service-side only, no database backstop — the V11/V22 posture). Idempotent via the
same `? 'kotlin'` containment check on `Languages` itself. `tags` is read back exclusively
through `Json.decodeFromString` (`TagCategoryService.kt`) and never string-compared, so
PostgreSQL's `jsonb` round-trip re-serializing the array with spaces is immaterial. Migration
checksums, including V32, are pinned in `MigrationChecksumTest`.

- `V33__seed_hierarchies` — seeds the new `HIERARCHY` dictionary (V7's table, a third `Dictionary`
  enum value) with the single value `composition` (position 0, the V8/V16 conflict idiom). NO
  default flag — `Dictionary.usesDefault` is false for HIERARCHY, and `validateDictionaryUpdate`
  rejects flagged items on it (the LIFECYCLE branch). `composition` is the one entity hierarchy
  the pre-1.32 single blueprint `hierarchyRelation` always described; V34 (below) backfills every
  existing pointer into this seeded map entry. Since V34 every blueprint write's `hierarchyRelations`
  keys must be ACTIVE entries here, and `DictionaryService.replace` refuses (409, naming the
  referrers) to remove or rename a value an active blueprint still names — see "Blueprint targets
  under concurrency (V27)" for the lock it takes to make that check hold.

**V34 — hierarchy relations map.** `ALTER TABLE blueprints ADD COLUMN hierarchy_relations TEXT NOT
NULL DEFAULT '{}'` (v1.32.0 — parallel entity hierarchies, see `.claude/docs/port-data-model.md`
"Toadie extensions"): ONE JSON object in TEXT (the `catalog_files.content`/`lenses.filters`
precedent, replaced whole on every save) mapping a hierarchy identifier — an ACTIVE value of the V33
`HIERARCHY` dictionary — to the relation key that is the entity's parent link in THAT hierarchy;
`{}` = the blueprint takes part in no hierarchy (ABSENT on the wire). The migration then backfills
`json_build_object('composition', hierarchy_relation)` into every row whose V29 pointer is set
(soft-deleted rows included — harmless, the column is replaced whole on the next save) and DROPS
`hierarchy_relation`. V31's INSERT still names the dropped column: it runs earlier on a fresh
database, so it stays valid and `_team` lands as `{"composition":"parent"}` through the backfill —
`SystemBlueprintTest` pins that. Still beside `definition`, never inside it, so the stored Port
document stays byte-identical (a Port export drops the column). No index, no CHECK: the key/value
rules live in `BlueprintValidation.kt` (value = a `many: false` relation KEY of the same row) and
`BlueprintService` (key = an active dictionary value, read inside the V27 locked transaction —
a sanctioned cross-feature table read of `DictionaryService.Entries`). One relation may serve
several hierarchies. Migration checksums, including V34, are pinned in `MigrationChecksumTest`.

### Soft delete (convention)

**V25 — authentication sessions.** `users.auth_version BIGINT NOT NULL DEFAULT 0` is the
monotonic credential/identity epoch, incremented atomically by password writes (including
bootstrap rotation) and email/role changes. Cosmetic and no-op profile writes preserve it. `auth_sessions` has a UUID-string
primary key, `user_id`, the captured epoch, and `expires_at`; the user/expiry indexes support
lookup and cleanup. It is ephemeral security state, not a soft-deleted business entity:
logout deletes a family and login prunes expired families. Ordinary acceptance joins the
session to the active user in one fresh database query. Registration/renewal lock the user
before touching the session and reject a stale epoch. Cleanup uses its own transaction to
avoid reversing that lock order. Renewal updates only an existing live family and never
shortens its expiry. No runtime DDL; prior migration checksums remain untouched.

`users`, `catalog_files`, `dictionary_entries`, `labels`, `annotation_keys`, `tag_categories`, `entity_types`, `lenses`, `blueprints`, and `entities` are **soft-deleted** — rows are flagged, never physically removed; every future business entity follows the same convention (except V31's two system blueprints, schema not content). Only join/audit/detail tables (today: `password_reset_tokens` (V26, expiring single-use credentials), `auth_sessions` (V25, expiring login families deleted at logout and pruned on login), `revoked_tokens`, a pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace, `graph_layouts` (V19) and its V30 twin `entity_graph_layouts`, both pure per-user settings rows whose PUT is a wholesale replace, and `catalog_file_events` (V23), the immutable audit trail itself — none carry history worth keeping, or ARE the history) hard-delete — a new hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. To add soft-delete to a new entity, follow the established pattern (reference implementations: `users/UserService.kt`, `catalog/CatalogFileService.kt` — the latter shows the full CRUD shape incl. the delete route):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — add `val markedAsDeleted = bool("marked_as_deleted").default(false)` and a private helper `fun active(): Op<Boolean> = <T>.markedAsDeleted eq false`.
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`, returning the affected-row `Int`; guard `update` mutations the same way. The route maps `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`) and delete stays idempotent in effect — `CatalogFileService.delete` + its route show the full shape; `UserService.deleteGuarded` adds the last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`: `CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A clash with an **active** row still raises `23505 → 409` (mapped centrally in `plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend `UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email` (`uq_users_email_active`, `V1`), the catalog-file identity (`uq_catalog_files_entity_active`, `V5` — an expression index over `LOWER(name)`, so identity is case-insensitive like Backstage's), the dictionary value (`uq_dictionary_entries_value_active`, `V7` — the whole-document replace soft-deletes omitted entries FIRST, so remove+re-add works in one save; the documented limitation is that swapping two values in one save trips the index → 409), the label key (`uq_labels_key_active`, `V10` — an expression index over `LOWER(key)`, so no case-twin keys), the tag-category name (`uq_tag_categories_name_active`, `V11` — same `LOWER(name)` shape; a category's TAGS are freed by soft-delete too, but through the service-side check, not an index), and the type-dictionary kind (`uq_entity_types_kind_active`, `V14` — plain `kind`, stored canonical; a soft-deleted dictionary frees its kind for a new one), and the annotation key (`uq_annotation_keys_key_active`, `V17` — the `LOWER(key)` labels shape), and the per-owner lens name (`uq_lenses_owner_name_active`, `V20` — `(created_by, LOWER(name))`, so uniqueness is scoped to the creator), and the per-blueprint entity identifier (`uq_entities_blueprint_identifier_active`, `V28` — `(blueprint_id, LOWER(identifier))`, so uniqueness is scoped to the owning blueprint and a soft-deleted entity frees its identifier within it; its `UNIQUE_CONSTRAINT_DETAILS` entry names the clash). (V9's `uq_dictionary_entries_default_active` is a partial unique index too — the at-most-one default backstop, not a freed business field — and has its own `UNIQUE_CONSTRAINT_DETAILS` entry.)

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` — the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column substring filter MUST use it. Also `jsonArrayContains` (parameter-bound `jsonb_exists` over a JSON TEXT column — the catalog list's tag filter), `jsonStringOrArrayContainsFolded` (v1.26.0 — case-insensitive match of a scalar value against a JSON string OR array column, e.g. entity `team` filter matching the stored value exactly; used only when the column value matches the exact search term, not for substring), and `orVanished` (post-commit read-back guard → 500, used by the catalog create). Lettuce's `requireValidReferences` (client-supplied-FK failures → 400) was dropped as unused — re-port it with the first route that takes a client-supplied foreign key.

**`infra/db/EventLog.kt` + `JsonParams.kt`** (ported from Lettuce with the first history trail, v1.15.0): the shared per-record audit-event machinery. A feature declares `object XEvents : EventLogTable("x_events", "x_id", XTable)` — an FK to the owning record, the acting `user_id`, a server-set `created_at`, `event_type VARCHAR(40)` (no CHECK — the Kotlin enum is the whitelist) and a `params TEXT` JSON `Map<String,String>` — and keeps only its typed `create`/`listFor` wrapper (`catalog/CatalogFileEventService.kt` is the first and so far only one). **Events are stored STRUCTURALLY so the SPA localizes them: no rendered string is ever stored.** Two deliberate departures from Lettuce's copy: its opt-in `commentColumn` hook (an encrypted free-text column on `goal_events`) was left behind — Toadie's events store no free text at all, recording only the FACT that a free-text field changed — and `listFor` is PAGED (`EventLogPage`), because a catalog file's event count is unbounded while Lettuce's per-record counts are intrinsically tiny (see `.claude/docs/list-endpoints.md`). Rows are IMMUTABLE: minted as a side-effect of the mutations, with no create/update/delete API.

**Atomic catalog mutations and product history.** Every catalog create, replacement, repo
sync, and soft delete appends its required structural history event inside the mutation's
own database transaction. The shared event insertion helper must use the caller's transaction;
never open another transaction or defer the event to the route. The actor is required for every
write. Failed event insertion rolls back the entire file write, including source reference,
sync baseline, timestamps, and deletion flag. A no-op replacement has no history event; a sync
always has one, and missing/deleted targets produce none. Existing structural params and
free-text redaction remain unchanged.

Replacement and sync lock the current file row before deriving the before/after diff, so
concurrent writes describe the state they actually replace. Keep these transactions short;
URL fetching stays outside them. This is per-file transaction consistency, not optimistic
concurrency control: competing full replacements still have last-write-wins behavior.

Import remains report-and-skip with one transaction per document. Each successful row commits
its file and CREATED event (`origin=import`) together. An unexpected storage/history failure
rolls back that row and returns ERROR without a fileId; other rows proceed, and earlier successful
rows remain committed. Cancellation stops further work without undoing completed rows. The
batch itself is not an all-or-nothing transaction, and sibling identities may still refer to a
row that later fails to store (the existing Errors-report residual).

Security `audit(...)` logs stay route-side after successful service returns; they are separate
from the transactional product history and are not an atomic external-delivery guarantee.
The create response's post-commit read-back can also fail after both file and event committed;
atomic storage does not promise that every failed HTTP response means nothing was committed.
This whole-feature convention deliberately supersedes Lettuce's split mutation/event shape.

### Not yet ported from Lettuce

- **Notifications**: port Lettuce's feature when it arrives, while retaining the atomic catalog mutation/history boundary above. External notification delivery needs its own explicit consistency decision.
