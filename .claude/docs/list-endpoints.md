### List endpoint conventions

> **Ported.** `server/src/main/kotlin/infra/paging/` (`Paging.kt` + `QueryParams.kt` +
> `PageResponse.kt` — `PageRequest`, `parsePaging`, `applyPaging`, `toPage`, the strict
> `singleValue` param reader and the `optionalString/Boolean/Enum` parsers — Lettuce's
> `optionalUInt`/`optionalLong` return with their first Toadie consumer) is
> Lettuce's implementation, ported with the first list endpoint (`GET /api/v1/files` —
> the reference implementation). Lettuce's view-scoped helpers (`optionalIncludeIndirect`,
> `uintOnlyForView`) were deliberately left behind; re-port them with their first consumer.
> The conventions below are the contract the package enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs) — summary:

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination, with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint whitelist → `400`; `id` asc always appended as tiebreaker; default `id` asc documented [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]` bracket operators only where needed; strict `true`/`false` booleans; enums by string name; repeated key reserved for `IN` — the params with documented `IN` semantics today are the catalog list/graph's `kind` (any-of over the supported kinds) and `labelValue` (any-of over the selected label's value), both read via `repeatedValues`; on every other scalar key repetition stays a `400` (Lettuce's `singleValue` rule — never silent first-value-wins; unknown parameter *names* stay ignored by deliberate leniency) [API-LIST-004].
- Free text: `q` first, per-column substring only when a UI requires it [API-LIST-005].
- Naming: camelCase params; reusable `Page`/`PageSize`/`Sort` under `#/components/parameters` `$ref`'d from each list path; one `*Page` envelope schema per resource; sortable/filterable whitelists documented per path [API-NAME-001..004].

**Implementation shape:** `ApplicationCall.parsePaging(sortable = setOf(...))` parses `page`/`pageSize`/`sort` from the query string against the per-endpoint whitelist into a `PageRequest` (appending the `id`-asc tiebreaker); `Query.applyPaging(req, columns)` applies `.orderBy(...)` + `.limit(...).offset(...)` to an Exposed `Query` via the service's file-level `SORTABLE_COLUMNS` map (the two whitelists must agree — a missing column is an `error()`, i.e. a 500-grade invariant violation). Validation failures throw `BadRequestException` → `400` + `ProblemDetail`. The service runs `count()` + the page rows against ONE shared predicate in ONE `suspendTransaction` and returns an `XListResult(items, total)`; the route responds `paging.toPage(items, total)` and the DTO file declares `typealias XPageResponse = PageResponse<XResponse>`. New list endpoints copy `catalog/` (service `list` + routes `get<X>` + the OpenAPI `$ref`s to the shared `Page`/`PageSize`/`Sort` parameters and a per-resource `*Page` schema) rather than re-parsing params.

**Sub-collections.** A record's own collection is paged like any other when it is unbounded: the catalog file's change history (`GET /api/v1/files/{id}/events`) rides the shared machinery through `EventLog.listFor` with its own `-timestamp,-id` default sort and a `{timestamp, id}` whitelist. This is a deliberate deviation from Lettuce, whose seven `*_events` endpoints answer an unpaged `{items}`: their per-record counts are intrinsically tiny, while a synced catalog file mints an event per sync run. Reserve the plain `{items}` wrapper for genuinely bounded sets (the registries, at most a few dozen rows).

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive — use `containsNormalized` (`infra/db/Sql.kt`, rendering `LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?))`, extension enabled in V4); never hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse breaks uppercase-diacritic input under a C-locale database). The SPA mirrors the rule client-side via the theme-level `foldedOptionsFilter` (`web/src/utils/text.ts`, wired as the Select/MultiSelect/TagsInput default filter in `theme.ts`).
