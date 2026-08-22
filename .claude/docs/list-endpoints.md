### List endpoint conventions

> **Not yet in Toadie.** The skeleton has no list endpoint and no `infra/paging` package. When the
> first list endpoint arrives, **port `server/src/main/kotlin/infra/paging/` from Lettuce
> verbatim** (`Paging.kt` + `QueryParams.kt` — `PageRequest`, `parsePaging`, `applyPaging`, the
> strict `singleValue` param reader) rather than re-implementing; the conventions below are the
> contract it enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs) — summary:

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination, with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint whitelist → `400`; `id` asc always appended as tiebreaker; default `id` asc documented [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]` bracket operators only where needed; strict `true`/`false` booleans; enums by string name; repeated key reserved for `IN` — and until an endpoint implements `IN`, a repeated scalar key is a `400` (Lettuce's `singleValue` rule — never silent first-value-wins; unknown parameter *names* stay ignored by deliberate leniency) [API-LIST-004].
- Free text: `q` first, per-column substring only when a UI requires it [API-LIST-005].
- Naming: camelCase params; reusable `Page`/`PageSize`/`Sort` under `#/components/parameters` `$ref`'d from each list path; one `*Page` envelope schema per resource; sortable/filterable whitelists documented per path [API-NAME-001..004].

**Implementation shape (once ported):** `ApplicationCall.parsePaging(...)` parses `page`/`pageSize`/`sort` from the query string against the per-endpoint whitelists into a `PageRequest`; `Query.applyPaging(req, columns)` applies `.limit(...).offset(...)` + `.orderBy(...)` to an Exposed `Query`. Validation failures throw a typed exception that `StatusPages` maps to `400` + `ProblemDetail`. New list endpoints reuse these rather than re-parsing params.

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive — port Lettuce's `containsNormalized` (`infra/db/Sql.kt`, rendering `LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?))`) together with its `unaccent`-extension migration when the first one arrives; never hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse breaks uppercase-diacritic input under a C-locale database). The SPA mirrors the rule client-side via a theme-level folded options filter (Lettuce's `foldedOptionsFilter`) — port it alongside.
