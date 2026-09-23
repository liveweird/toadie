# Quality check-up — 2026-09-24

Reviewed `master` at `c1f0516` (v2.14.0), with emphasis on integration GraphQL,
ontology reads and source sync, catalog revision writes, the Port SPA, API contracts,
and deployment assumptions. This was a read-only, risk-focused review. Its five
findings came from concrete code paths and concurrency schedules; the initial review
did not reproduce them against a running stack. No P1 finding was supported.

This is the disposition ledger for **this** check-up. The August reviews and the
historical hardening plan are separate. Keep each row's status current when its fix
is verified; a release note alone does not close an untested behavior.

| ID | Finding and trigger | Status |
| --- | --- | --- |
| P2-1 | An ontology page could combine definitions, totals, rows, and revision from different database snapshots; equal revisions could then certify a mixed multi-page scan. | Fixed in v2.14.1 (`53839c7`): page reads use one repeatable-read snapshot, with concurrency regressions. |
| P2-2 | Entity sync could confirm a fetched replacement before current detail, sync state, and preflight were ready; blueprint sync could also confirm without completed preflight. | Fixed in v2.14.2 (`5f97e66`): confirmation requires a complete, successful review of the submitted document. |
| P2-3 | A Port sync modal could fetch obsolete source A, then store its content as the baseline for the row's current source B. | Fixed in v2.14.2 (`5f97e66`): fresh-source selection and a transactional expected-source guard reject retargeting. |
| P3-1 | Hiding every blueprint pill on Entity Errors suppresses the whole report, including broken saved queries that blueprint filters must not affect. | Fixed in v2.14.3: an empty-scope request retains saved-query diagnostics; page and browser regressions cover the all-hidden state. |
| P3-2 | Entity Graph styles all edges with a selected hierarchy relation *name* as hierarchy edges, including unrelated edges with the same name on another blueprint. | Fixed in v2.14.3: concrete-edge membership drives emphasis; utility and page regressions cover the name collision. |

One additional observation was **not** counted among the five findings: all three
`/import/check` endpoints ignore an invalid batch-level `sourceUrl` that real import
rejects. The published contract documents that distinction. Aligning full-request
preflight with import is an optional product improvement requiring a deliberate
contract and test update, not a validation bypass or a scheduled fix.

The P3 behaviors were rechecked on `master` at `47b6982` before this changeset:
`web/src/pages/EntityErrors.tsx` disabled/discarded the report when `noBlueprints`,
and `web/src/pages/EntityGraph.tsx` derived styling from a global set of relation
names. After the fixes, all 3,129 frontend coverage tests and the focused real-browser
Entity Errors and Entity Graph journeys passed; frontend lint, Knip, build, E2E typecheck and scenario
parity also passed. Independent cross-review found no remaining production defect;
its E2E locator-stability finding was corrected before the browser run. The two
existing untracked handoff files are unrelated and are not part of the check-up.
