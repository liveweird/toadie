# Entity query language (offline reference)

**This is the local reference for Toadie's entity query language** — phase 7 of the Port
data-model move (2.0.0): a read-only, openCypher-shaped SUBSET that narrows the Entity graph
and Entity hierarchy canvases to the entities a query returns. It is deliberately small: node
sets in, node sets out, evaluated IN MEMORY over the per-request entity snapshot, under a
cooperative budget. The implementation is the flat package `server/src/main/kotlin/entityquery/`
(`package ch.nokillswit.entityquery`): `QueryLexer.kt` → `QueryParser.kt`/`PatternParser.kt`/
`ExpressionParser.kt` (`parseEntityQuery`) → `QueryValidator.kt` (`validateEntityQuery`,
`Suggestions.kt`) → `QueryEvaluator.kt` (`InMemoryQueryExecutor` over `QueryGraph.kt`'s
`InMemoryQueryGraph`, values in `QueryValues.kt`, the budget in `QueryBudget.kt`); the caps,
diagnostic codes and wire DTOs live in `EntityQuery.kt`. Every one of those files is pure —
no database, no Ktor — so their tests run without Docker. Consult this file when changing the
grammar, a semantic rule, a cap, or a diagnostic; update it in the same change.

## Why this shape

Users want to focus large, densely connected ontologies (hundreds of components, tens of
databases and topics, heavy metadata) on the subset they care about. The `blueprint`/`q`/`team`
pills answer "which rows", never "which rows reachable how". A graph query language answers
that — and openCypher's pattern syntax is the one users already read. The caps that already
bound the workspace (`MAX_BLUEPRINTS` 200, `MAX_ENTITIES_TOTAL` 10 000) make a graph database,
an edge table or a jsonb index infrastructure for a scale the app forbids: one `SELECT` of the
active rows, indexed once per request, is well inside the budget (see "Performance" below).
Decisions, all taken 2026-09-12 and pinned here:

- **Hand-written recursive descent** (no ANTLR, no new server dependency): the subset is small,
  every rejection message is ours, and the parser stops at its FIRST syntax error (a recursive-
  descent parser cannot resynchronize meaningfully); the validator reports EVERY finding it can.
- **In memory, behind interfaces** (`QueryExecutor`, `QueryGraph`) so a different executor can
  arrive later without touching the parser, the validator or the service.
- **RETURN yields entities only** — the variables listed (or `*`) bind entities; those entities
  become the SHOWN set. No projections, no aggregates, no functions.
- **WHERE sees STORED properties + the seven metas only** (`$identifier`, `$title`, `$blueprint`,
  `$team`, `$icon`, `$createdAt`, `$updatedAt`): mirror/calculation/aggregation values are
  evaluated per response (phase 5) and never stored, exactly as the list's `q`/sort/filter never
  see them (`.claude/docs/list-endpoints.md`).
- **Surfaces**: the Entity graph and Entity hierarchy canvases share ONE query bar (one stored
  draft, one applied query — `entityQuery.text`/`entityQuery.applied`); the Entities list keeps
  its pills only.
- **Transport**: `GET /api/v1/entities/graph?query=…` (`maxLength` 2000) — the query is one more
  filter on the graph read, cacheable by the SPA like the others; the request line is raised to
  16 KiB (`ktor.deployment.maxInitialLineLength`, `.claude/docs/security.md`) because a
  2000-character query URL-encodes to roughly 6 KB against Netty's 4 KiB default.
- **A budget miss is a 400**, not a 503/504: `DEADLINE_EXCEEDED`/`BINDING_LIMIT` are semantic
  rejections of THIS query (rewrite it narrower), rendered by the one `InvalidPayloadException`
  handler like every other findings-bearing 400.
- **Regex `=~` is out**: `java.util.regex` cannot honour the cooperative deadline (catastrophic
  backtracking runs to completion) — `CONTAINS`/`STARTS WITH`/`ENDS WITH` cover the need.
- **Filters interplay**: the query runs over the FULL active workspace — a traversal may pass
  THROUGH entities the `blueprint`/`q`/`team` filters hide ("hidden is not absent", the catalog
  graph's rule) — and its result is INTERSECTED with the filters' shown set; the both-ends rule
  is unchanged, so an edge is drawn only between two shown entities.

## Lexical structure

- Keywords are case-insensitive (`match`, `MATCH`). `IDENT = [A-Za-z_][A-Za-z0-9_]*`; any other
  name — one with `-`, `.`, `:`, `/`, a leading digit — is backticked: `` `web-service` ``
  (a backtick inside is doubled). Backticked names are byte-exact.
- `META = $IDENT` — accepted only after `.` (`v.$title`), as a property-map key
  (`{$identifier: 'x'}`), or as an edge type (`$team`).
- Strings `'…'` or `"…"` with the escapes `\\ \' \" \n \t \r \uXXXX` (exactly four hex digits).
- Numbers: integers and decimals; a `-` immediately before a number in operand position is a
  negative literal (`a.x < -1`). An integer beyond `Long` is a `SYNTAX` error.
- Comments `// …` (to end of line) and `/* … */`.
- Two-character tokens `<= >= <> != ..`; arrows are assembled by the pattern parser from
  offset-ADJACENT `-`, `<`, `>` tokens, so `a < -1` in WHERE is a comparison, never an arrow.
- Positions are 1-based; a `Span(line, column, endLine, endColumn)` is end-exclusive. Every
  positioned diagnostic carries one.

## Grammar (2.0.0)

```
query          := matchClause+ optionalClause* returnClause limitClause? EOF
matchClause    := MATCH pattern (',' pattern)* whereClause?
optionalClause := OPTIONAL MATCH pattern (',' pattern)* whereClause?
pattern        := node (edge node)*
node           := '(' variable? (':' label ('|' label)*)? propertyMap? ')'     -- label = blueprint identifier (case-folded)
edge           := '-' body? '->' | '<-' body? '-' | '-' body? '-'              -- bare '-->' '<--' '--' = body omitted
body           := '[' variable? (':' edgeType ('|' edgeType)*)? range? ']'     -- edgeType = relation key | hierarchy id | $team
range          := '*' (INTEGER? ('..' INTEGER?)?)?                             -- '*' = 1..10, '*n' = n..n, '*0..' allowed
propertyMap    := '{' (propertyKey ':' literal (',' propertyKey ':' literal)*)? '}'  -- equality predicates
whereClause    := WHERE expression
expression     := orExpr
orExpr         := andExpr (OR andExpr)*
andExpr        := notExpr (AND notExpr)*
notExpr        := NOT notExpr | predicate
predicate      := '(' expression ')' | operand comparison?                    -- bare operand: TRUE iff JSON true
comparison     := ('=' | '<>' | '!=' | '<' | '<=' | '>' | '>=') operand | IN operand | CONTAINS operand
                | STARTS WITH operand | ENDS WITH operand | IS NULL | IS NOT NULL
operand        := variable '.' propertyKey | literal
propertyKey    := name | META
literal        := STRING | '-'? NUMBER | TRUE | FALSE | NULL | '[' (literal (',' literal)*)? ']'
returnClause   := RETURN DISTINCT? ('*' | variable (',' variable)*)            -- DISTINCT accepted as a no-op
limitClause    := LIMIT INTEGER
name           := IDENT | BACKTICK_IDENT
```

Examples over the baseline ontology (`.claude/docs/ontology.md`):

```
MATCH (s:service)-[:owned_by]->(t:_team) WHERE t.$identifier = 'platform' RETURN s, t
MATCH (s:service)-[:depends_on*1..3]->(d) RETURN s, d
MATCH (c)-[:composition*]->(sys:system {$identifier: 'billing'}) RETURN c, sys
MATCH (e)-[:$team]->(t:_team) WHERE e.lifecycle IN ['sunsetting', 'deprecated'] RETURN e, t
MATCH (a:api) OPTIONAL MATCH (a)<-[:provides_api]-(s:service) RETURN a, s LIMIT 50
```

## Rejected features — each a fixed `UNSUPPORTED` message

| Feature | Message |
|---|---|
| `CREATE MERGE SET DELETE DETACH REMOVE CALL WITH UNWIND FOREACH LOAD UNION ORDER SKIP CASE XOR` | `` `WITH` is not supported — Toadie 2.0.0 accepts MATCH, OPTIONAL MATCH, WHERE, RETURN and LIMIT only `` |
| `$param` parameters | `query parameters are not supported` |
| function calls (`toLower(a.x)`, `count(a)`, `size(...)`) | `functions are not supported` |
| path variables `p = (…)` | `path variables are not supported` |
| multiple labels `:A:B` | `` an entity has exactly one blueprint — use `:A|B` for alternatives `` |
| `WHERE` inside a node pattern | `WHERE is not supported inside a node pattern` |
| projections, `AS`, aggregates in RETURN | `RETURN yields entities only — list variables or use *` |
| a plain `MATCH` after an `OPTIONAL MATCH` | `a plain MATCH after OPTIONAL MATCH is not supported — put every plain MATCH first` |
| regex `=~` | a `SYNTAX` error at the operator (use `CONTAINS` / `STARTS WITH` / `ENDS WITH`) |
| an edge variable in WHERE / RETURN | `RELATIONSHIP_VARIABLE_REFERENCE` — `relations carry no properties in Toadie` |

Every reserved word (accepted or rejected) is refused as a variable or property name — backtick
it if a blueprint really is called `` `order` ``.

## Semantics

**Names.** Labels (blueprint identifiers) and hierarchy ids fold case, exactly as the
`blueprint` filter does; relation keys, property ids and string literals are BYTE-EXACT (Port
maps are case-sensitive). An unknown label, relation or property is a STRICT error (Cypher would
answer an empty/null result) — the same posture as every registry check.

**Edge types resolve PER SOURCE BLUEPRINT** to relation keys: `t` names `{t}` when it is a
relation key of the source blueprint, ∪ `{hierarchyRelations[t]}` when it is a hierarchy id (a
VIRTUAL edge type, child → parent, so `-[:composition]->` follows whichever relation each
blueprint declared for that hierarchy — different keys on different blueprints), or the ownership
pseudo-edge when it is `$team` (entity → its `_team` entities, the canvases' `$team` edge). A
bare arrow (`-->`, `<--`, `--`) is every declared relation of the source PLUS `$team` — exactly
what the canvas draws. Direction `IN` resolves the type against the NEIGHBOUR's blueprint;
`UNDIRECTED` is outgoing ∪ incoming.

**`$team` is the EFFECTIVE team as an array** — the stored value for Direct/absent ownership,
the value computed along `ownership.path` for Inherited ownership (`entities/EntityOwnership.kt`'s
`effectiveTeam`), and `$team` edges/owners match team identifiers BYTE-EXACT, exactly like
`buildEntityGraph`'s ownership edges and the `TEAM_TARGET_MISSING` finding (never folded — the
`team` FILTER folds, the graph edges do not).

**Variable-length hops** are LEVEL-SET BFS: one frontier per depth, deduplicated within a level,
`*` alone = `1..10`, `*n` = exactly level n, `*n..m` = a node that appears at some level within
`n..m` (so `*2..2` matches even when a shorter path also exists — LEVELS, not path enumeration),
`*0..` includes the start node. Deliberate divergences from Cypher: no path enumeration, no
relationship uniqueness (an undirected `*2` can bounce back to the start), no path variables.

**WHERE** is Kleene three-valued: a comparison against `null`/an absent property/an incomparable
pair (a string against a number) is UNKNOWN; `AND`/`OR`/`NOT` follow the Kleene tables; a
binding is kept iff the expression is TRUE. `=`/`<>` are structural (`JsonElement.equals`) with
numbers widened to `Double` first, so `1 = 1.0` is TRUE; `< <= > >=` compare numbers as doubles
and strings lexicographically (byte order); `IN` takes a list literal OR an array-valued property
on EITHER side; `CONTAINS`/`STARTS WITH`/`ENDS WITH` are string-only (UNKNOWN otherwise); a bare
operand is TRUE only for JSON `true`. Inline property maps `{k: v}` are equality predicates.
Expression nesting is capped at 64 levels (`MAX_EXPRESSION_DEPTH`, a `SYNTAX` error beyond).

**Clauses.** Plain `MATCH` patterns are inner joins anchored on already-bound variables, else on
the most constrained node (label + `$identifier` → one lookup; label + properties → a filtered
blueprint scan; labels → the blueprint's rows; unlabelled → every row), expanding right and left
of the anchor. Patterns that share no node variable across the plain clauses are refused
(`DISCONNECTED_PATTERN`) — no cartesian products. An unlabelled re-reference `(a)` of a variable
bound as `(a:service)` keeps the labels it was bound with; a truly unlabelled source accepts a
relation when ANY blueprint with that key reaches the far label, and a labelled pair is checked
end to end ("relation `r` of `X` targets `Y`, not `Z`"). `OPTIONAL MATCH` clauses come last, must
reuse a variable of a preceding `MATCH`, extend each binding (their own `WHERE` applies inside
the extension, Cypher's rule), leave the new slots `null` when nothing extends — and an OPTIONAL
MATCH anchored on a slot that is already `null` yields no extension. `RETURN` is the ordered set
(`LinkedHashSet`) of the non-null entities bound to the listed variables (`*` = every node
variable) — a `_team` reached via `$team` is returned like any entity; `LIMIT n` cuts that set
AFTER deduplication, `1..MAX_ENTITIES_TOTAL` (`LIMIT_INVALID` otherwise). The result is a node
SET, never rows: two bindings of the same entity count once.

## Caps and the budget (`EntityQuery.kt`)

| Cap | Value | Refusal |
|---|---|---|
| `MAX_QUERY_LENGTH` | 2000 characters | route-level plain `400` (`maxLength` in the contract); `QUERY_TOO_LONG` inside the engine |
| `MAX_QUERY_NODE_PATTERNS` | 32 node patterns across every clause | `TOO_MANY_PATTERNS` |
| `MAX_QUERY_VARIABLES` | 32 distinct node + edge variables | `TOO_MANY_VARIABLES` |
| `MAX_QUERY_HOPS` | 10 (`*` alone = `1..10`; a larger explicit bound) | `RANGE_INVALID` |
| `MAX_QUERY_BINDINGS` | 100 000 intermediate join rows, checked after EVERY produced row | `BINDING_LIMIT` |
| `entityQuery.deadlineMillis` | `$ENTITY_QUERY_DEADLINE_MILLIS:2000`, boot-validated `1..60000` (`MAX_ENTITY_QUERY_DEADLINE_MILLIS`) | `DEADLINE_EXCEEDED` |
| `MAX_STRING_OPERAND_CHARS` / `MAX_STRING_NEEDLE_CHARS` | 16 384 / 256 characters for the string operators | the comparison is UNKNOWN (never an error) |
| `MAX_CONCURRENT_ENTITY_QUERIES` | 4 in-flight evaluations per instance | `429 Too Many Requests` (plain problem, no diagnostics) |
| `MAX_SUGGESTION_INPUT_CHARS` | 128 — an unknown name longer than any identifier gets no suggestion | (no refusal) |

`QueryBudget` is COOPERATIVE: the evaluator calls `checkpoint()` per produced candidate, and
EVERY call checks caller cancellation (`ensureActive`) and the clock (a `nanoTime` read is far
cheaper than producing a candidate). Nothing can preempt a candidate mid-way, so per-candidate
work is itself capped: `CONTAINS`/`STARTS WITH`/`ENDS WITH` answer UNKNOWN when the haystack
exceeds `MAX_STRING_OPERAND_CHARS` (16 384) or the needle `MAX_STRING_NEEDLE_CHARS` (256) —
`String.contains` is O(haystack × needle) in the worst case, and two 256 KiB properties would
otherwise be minutes of uninterruptible work. The deadline is therefore honoured within one
candidate's bounded work; there is deliberately NO outer `withTimeout` (it could only cancel at
the same checkpoint). `EntityService.graph` runs each evaluation on the dedicated `entity-query`
pool (`MAX_CONCURRENT_ENTITY_QUERIES` = 4 daemon threads, never `Dispatchers.Default`, which
bcrypt and the request pipeline share) AFTER the read transaction closed (the phase-5 rule: CPU
work never pins a pooled connection or the entity lock — `.claude/docs/persistence.md`), and
hands out the same number of permits, taken AFTER validation and before the workspace read: a
valid query arriving while four evaluations are in flight answers `429` immediately (a refused
query costs no permit), since each in-flight evaluation holds a decoded workspace on a 256 MiB
heap. Caller cancellation propagates unchanged; nothing is
quarantined or cached across requests.

## Diagnostics (`QueryDiagnosticCodes`)

One wire shape, `QueryDiagnostic {code, message, line?, column?, endLine?, endColumn?,
suggestion?}`, rides both the graph GET's `400` (`EntityQueryProblem` — `ProblemDetail` plus
`diagnostics`) and `POST /api/v1/entities/query/check`'s `200` (`EntityQueryCheckResponse`).
Every code is an ERROR — a query is accepted whole or refused. Diagnostics are sorted by
position. `SYNTAX` (lexical/grammatical, the parser's FIRST error only), `UNSUPPORTED` (the table
above), `UNKNOWN_LABEL` (with a `suggestion` from `Suggestions.kt` — case-folded Levenshtein ≤
max(2, len/3) or a prefix/substring match, alphabetical tie-break, over identifiers AND titles —
always the IDENTIFIER, and never embedded in `message`: the SPA renders "Did you mean `x`?" once
from the field), `UNKNOWN_RELATION` (direction-aware, target-aware when both
ends are labelled), `UNKNOWN_PROPERTY` (the label's `schema.properties` ∪ the seven metas; the
union when unlabelled; suppressed when the label itself is unknown), `UNKNOWN_VARIABLE`,
`DUPLICATE_VARIABLE` (re-bound with different labels, or as a different kind),
`RELATIONSHIP_VARIABLE_REFERENCE`, `RANGE_INVALID`, `LIMIT_INVALID`, `DISCONNECTED_PATTERN`,
`TOO_MANY_PATTERNS`, `TOO_MANY_VARIABLES`, `QUERY_TOO_LONG`, and the two positionless
evaluation refusals `DEADLINE_EXCEEDED`/`BINDING_LIMIT`.

## API

- `GET /api/v1/entities/graph?query=…` — any authenticated user (the entities shared-workspace
  rule). The service reads the blueprints + hierarchies in one short transaction, parses and
  validates OUTSIDE it (a refusal never reads an entity row, and the suggestion scan never
  holds a pooled connection), takes a permit (none free → `429`, before the workspace read; a
  refused query never costs one), then loads the WHOLE active workspace (`EntityService.loadWorkspaceSnapshot`, every active row of
  every active blueprint, still undecoded) in a second transaction, and decodes + evaluates on
  the `entity-query` pool after it closed, keeping the filtered rows whose `(blueprint,
  identifier)` the query returned. Blank `query` = absent; repetition and over-length are plain
  `400`s. Not audited (a pure read). The query text rides the request line, so — like `q` — it
  appears in access/proxy logs and browser history; the SERVER never logs it.
- `POST /api/v1/entities/query/check {query}` → `200 {diagnostics}` — the editor's live check:
  parse + validate against the CURRENT blueprints and `hierarchies` dictionary (read in one
  short transaction; the validation itself runs after it), nothing evaluated (so never
  `DEADLINE_EXCEEDED`/`BINDING_LIMIT`), blank = `[]`. Not audited.
- The hierarchy ids come from the active `HIERARCHY` dictionary rows (`DictionaryService.Entries`,
  a sanctioned cross-feature read inside the graph's read transaction).

## The SPA (`web/`)

CodeMirror 6 (`@codemirror/*`, MIT, bundled same-origin — the CSP's `script-src 'self'` holds;
its injected styles ride the existing `style-src 'unsafe-inline'`): `utils/queryLanguage.ts` is
a `StreamLanguage` tokenizer for highlighting (no Lezer grammar build step), `utils/
queryCompletion.ts` the schema-aware completion source (blueprints by title after `(v:`, relation
keys ∪ hierarchy ids ∪ `$team` in an edge body, properties + metas after `v.`, `enum` values after
`v.prop =`/`IN [`, clause keywords at clause starts, backticks via `quoteIfNeeded`), `utils/
queryDiagnostics.ts` the server-diagnostic → lint-marker mapping; `components/QueryEditor.tsx`
wraps the `EditorView`, `components/EntityQueryBar.tsx` adds Run (Mod+Enter), Clear, the
"Applied · N" badge and the diagnostics list; `hooks/useEntityQuery.ts` holds the ONE shared
draft/applied pair, `hooks/useQueryDiagnostics.ts` the 300 ms-debounced `/query/check`. Running
happens only on Run / Mod+Enter (and on load when an applied query is stored); diagnostics on
every keystroke, debounced.

## Performance and the snapshot-cache trigger

At the caps: 10 000 rows → one `SELECT` plus 10 000 `blueprintJson` decodes (~50–150 ms; today's
unfiltered graph already loads whole blueprints' rows), index build O(rows × relations), BFS ≤ 10
levels × degree, joins bounded by `MAX_QUERY_BINDINGS` — well inside the 2 s budget;
`QueryEvaluatorScaleTest` pins correctness at 10k rows under a GENEROUS bound, never a timing.
**No snapshot cache in 2.0.0.** The documented trigger for adding one: a measured p95 above
~500 ms for `GET …/graph?query=` on the real workspace where the DB read + decode dominates →
a version-stamped per-instance cache keyed on `(count, max(updated_at))` of the active rows.

## Tests

Pure, no Docker: `QueryLexerTest`, `QueryParserTest` (one case per production, EVERY rejection
message, every cap), `QueryValidatorTest` (each code with position + suggestion), `SuggestionsTest`,
`QueryValuesTest` (the Kleene table, mixed types, `IN` both ways, `1 = 1.0`), `QueryEvaluatorTest`
(direction, undirected, bare arrow incl. `$team`, `*` bounds incl. `*0..` and the shortest-level
case, hierarchy virtual edges across two blueprints with DIFFERENT keys, ownership incl. an
Inherited effective team, inline props, WHERE operators, joins, OPTIONAL null-keeping incl. a
chain through a null slot, dedupe, LIMIT after dedupe, the binding cap, the deadline via an
injected clock, caller cancellation propagating), `QueryEvaluatorScaleTest` (10k rows),
`QueryTckCasesTest` (26 hand-transcribed openCypher TCK scenarios). Route-level, in
`EntityQueryRouteTest`:
the query narrowing the graph under the both-ends rule, traversal THROUGH a hidden entity, `$team`
with an Inherited team, a hierarchy virtual edge across two blueprints, `UNKNOWN_LABEL` with
position + suggestion, `UNSUPPORTED`, the over-length plain `400`, `/query/check` parity + `401`
+ no audit event, a deterministic `BINDING_LIMIT`, and — through `TestEntities.queryService`'s
injected clock/permits — `DEADLINE_EXCEEDED` on the first checkpoint past the deadline, caller
cancellation propagating as a `CancellationException`, and the one-permit `429` that recovers
once the permit frees; `EntityQueryDeadlineConfigTest` (boot range),
`ProductionHttpTest` (a ~6 KB request line is parsed). Frontend: the pure-module tests, the
editor smoke, the page tests through a mocked editor; e2e `entity-query.spec.ts`.
