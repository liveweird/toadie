### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in `application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service (host port **5433** — Lettuce may occupy 5432 on the same machine; in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived JDBC connection. Migrations are the single source of truth for schema; do not call `SchemaUtils.create` anywhere.
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access. `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the services and publishes them into `Application.attributes` (`UserServiceKey`, `TokenBlocklistServiceKey`, `CatalogFileServiceKey`); each service itself lives next to the feature it serves (`users/UserService.kt`, `auth/TokenBlocklistService.kt`, `catalog/CatalogFileService.kt`). The Exposed table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently wrap them); one wrinkle: V1 created `users.id` as `BIGSERIAL` (64-bit in SQL, 32-bit everywhere above it) while V5 uses `SERIAL` — harmless at this scale, documented so nobody "fixes" one to match the other without a migration.

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries go through R2DBC.

**Cross-feature table reads (the service-layer rule, inherited from Lettuce).** A feature service MAY query another feature's Exposed table objects directly when the read must run **inside its own transaction** (SQL joins, atomic snapshots) — calling the other feature's *service* would open a second transaction and break atomicity. Route handlers never touch tables (services only). The first such read is `CatalogFileService.joined()` (catalog list/read join `UserService.Users` for the creator's display fields).

Current migrations are `V1`–`V6` — small enough that this section is the catalog (Lettuce splits it into `.claude/docs/features/migrations.md`; introduce that file when the count warrants it):

- `V1__init` — the `users` table: `name` (≤50), `email` (≤254), `password_hash`, `role` with `CHECK ("role" IN ('ADMIN', 'USER'))` (single-column role storage; the wire shape stays a `roles` set, see `.claude/docs/authorization.md`), `password_changed_at` (epoch millis, 0 = never — `/refresh` rejects older tokens), `marked_as_deleted`; plus the partial unique index `uq_users_email_active` over active rows.
- `V2__create_revoked_tokens` — the JWT blocklist for `/logout`: `jti` PK + `expires_at`, with an index on `expires_at` (the revoke path prunes expired rows opportunistically, so the table stays tiny).
- `V3__seed_admin` — the bootstrap administrator `admin@toadie.local` / `changeme`, idempotent via `ON CONFLICT DO NOTHING`; production neutralizes it at startup (see "Default admin" in `.claude/docs/security.md`).
- `V4__enable_unaccent_extension` — Lettuce's unaccent migration, backing every `containsNormalized` substring filter (see `infra/db/Sql.kt`).
- `V5__create_catalog_files` — stored catalog-info.yaml documents (one row = one entity; structured JSON in `content`, identity columns `kind`/`name`/`namespace` denormalized), `created_by` FK to `users`, epoch-millis `created_at`/`updated_at`, and the partial unique index `uq_catalog_files_entity_active` over `(kind, namespace, LOWER(name))` — Backstage's case-insensitive identity, active rows only.
- `V6__widen_catalog_kinds` — the kind CHECK grows from Component-only to the seven landscape kinds (API, System, Domain, Resource, Group, User join; Location/Template deliberately out).

### Soft delete (convention)

`users` and `catalog_files` are **soft-deleted** — rows are flagged, never physically removed; every future business entity follows the same convention. Only join/audit/detail tables (today: `revoked_tokens`, a pure token registry) hard-delete — a new hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. To add soft-delete to a new entity, follow the established pattern (reference implementations: `users/UserService.kt`, `catalog/CatalogFileService.kt` — the latter shows the full CRUD shape incl. the delete route):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — add `val markedAsDeleted = bool("marked_as_deleted").default(false)` and a private helper `fun active(): Op<Boolean> = <T>.markedAsDeleted eq false`.
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`, returning the affected-row `Int`; guard `update` mutations the same way. The route maps `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`) and delete stays idempotent in effect — `CatalogFileService.delete` + its route show the full shape; `UserService.deleteGuarded` adds the last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`: `CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A clash with an **active** row still raises `23505 → 409` (mapped centrally in `plugins/ErrorHandling.kt`). In place today: `users.email` (`uq_users_email_active`, `V1`) and the catalog-file identity (`uq_catalog_files_entity_active`, `V5` — an expression index over `LOWER(name)`, so identity is case-insensitive like Backstage's).

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` — the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column substring filter MUST use it. Also `orVanished` (post-commit read-back guard → 500, used by the catalog create). Lettuce's `requireValidReferences` (client-supplied-FK failures → 400) was dropped as unused — re-port it with the first route that takes a client-supplied foreign key.

### Not yet ported from Lettuce

- **`EventLog` / per-record history tables**: port the shared `EventLogTable` machinery from Lettuce with the first feature that keeps a history trail.
- **The mutation → history-event → notification consistency model** (deliberately non-atomic, documented in Lettuce's persistence doc): adopt Lettuce's shape wholesale when events/notifications arrive — don't design a variant.
