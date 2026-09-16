# Documentation and quality guardrails review — 13 September 2026

Reviewed `master` at `2ff74ebbaaded1f1ffcb0237927027b5a713178e` (v2.4.0).
This report records a read-only review of project guidance against representative code,
tests, check scripts, CI, and GitHub branch rules. Creating this report is the only
repository change made for this request. No recommendations have been implemented;
no repository settings, application data, containers, or deployments were changed.

This is a review snapshot for Claude to assess, not an approved implementation plan
or a replacement for [HARDENING.md](HARDENING.md). Previously parked work remains
parked. The earlier application audit is [QUALITY_REVIEW.md](QUALITY_REVIEW.md).
References below describe the reviewed revision; recheck them before implementation.

## Assessment

The documentation is specific about existing domain and security behavior, but less
reliable as guidance for extending the solution. Its strongest rules should be
preserved: migration immutability, authorization precedence, database locking,
bounded processing, OpenAPI response validation, coverage gates, isolated test data,
and bilingual UI conventions.

The main improvement is not more prose. It is fewer conflicting instructions,
one authoritative home per rule, task-specific working examples, and an explicit
distinction between requirements that are enforced, reviewed manually, or excepted.
No documentation can guarantee quality; checks and focused independent review must
support it.

## Findings supported by repository evidence

### DQ-01 — Generic validation instructions conflict with ownership-sensitive mutations

**Observation:** The “Feature template — copy `catalog/`” paragraph in
[CLAUDE.md](CLAUDE.md) requires `validateX` in both route and service.
[LensRoutes.kt](server/src/main/kotlin/lenses/LensRoutes.kt), however, deliberately
passes the typed PUT request to
[LensService.update](server/src/main/kotlin/lenses/LensService.kt) without first
sanitizing or semantically validating it. The service decides ownership and visibility
before validation, preserving `403`/`404` precedence over `400`.

**Risk:** Following the broad template can regress error/disclosure precedence when
an unauthorized caller submits semantically invalid fields. This is a misleading
prescription, not a finding that the current lens implementation is defective.

**Recommendation:** Make service validation authoritative. Permit route validation
only after all applicable authorization and disclosure decisions. Explicitly
distinguish decoding a request from resource-dependent semantic validation. Link the
lens implementation and its tests as the owned-resource mutation example.

### DQ-02 — API status guidance and locking descriptions disagree with implementation

**Observation:** `API-ERR-002` in
[API-GUIDELINES.md](api-guidelines/API-GUIDELINES.md) prescribes `422` for semantically
invalid, well-formed entities. The findings-bearing domain rejection handler in
[ErrorHandling.kt](server/src/main/kotlin/plugins/ErrorHandling.kt) returns `400`.
The blueprint description in [CLAUDE.md](CLAUDE.md) says mutations operate under the
tag-category table lock, while
[BlueprintService.writeTransaction](server/src/main/kotlin/blueprints/BlueprintService.kt)
uses the blueprint lock. The canonical cooperating-writer protocol is documented in
[persistence.md](.claude/docs/persistence.md).

**Risk:** Developers can introduce incompatible response conventions or copy the wrong
concurrency assumptions while believing they are following authoritative guidance.

**Recommendation:** Clarify the intended Toadie API status policy, registering a
project exception if the general guideline remains unchanged. Do not change public
responses merely to reconcile prose. Correct the lock description and link to the
canonical protocol instead of restating it in multiple places.

### DQ-03 — “Code wins” does not distinguish facts from normative guarantees

**Observation:** [AGENTS.md](AGENTS.md), under “Sources of Truth,” says configuration
and code win when documentation disagrees.

**Risk:** Read literally, this can encourage changing documentation or tests to bless
a bug that violates a security invariant or API guarantee.

**Recommendation:** Executable configuration establishes descriptive facts such as
tool versions and registered modules. A conflict involving a normative guarantee
requires investigation, a regression test where appropriate, and an explicit
decision about which artifact is wrong. Existing code alone is not that decision.

### DQ-04 — The documented Quality gate is not required by GitHub branch rules

**Observation:** The GitHub API inspection during this review showed deletion,
non-fast-forward, and pull-request rules for `master`, but no required-status-check
rule. The branch protection response also reported no required check contexts.
The aggregate gate in [.github/workflows/ci.yml](.github/workflows/ci.yml) exists and
checks backend, frontend, and E2E results. Its presence does not require a successful
result before merging.

**Impact:** Passing CI is documented as a merge requirement but is not enforced by
the inspected repository configuration. This does not mean `master` is unprotected.

**Recommendation:** When the user elects to resume this work, require the aggregate
Quality gate in GitHub settings and verify the effective rule. This is already an
open item in [HARDENING.md](HARDENING.md), not newly authorized work.

**Recheck commands:**

```sh
gh api repos/liveweird/toadie/branches/master
gh api repos/liveweird/toadie/rules/branches/master
```

### DQ-05 — Some checks enforce less than the documentation implies

**Scenario parity:** [testing.md](.claude/docs/testing.md) and [AGENTS.md](AGENTS.md)
associate `check:scenarios` with the same-commit scenario and README coverage-map
requirement. [check-scenarios.mjs](e2e/scripts/check-scenarios.mjs) checks spec/scenario
files and titles but never reads the README coverage map. Either narrow the claim
or extend the checker to cover the promised inventory.

**API lint:** `security-is-declared` and `security-schemes-defined` in
[the Spectral ruleset](api-guidelines/api-guidelines.spectral.yaml) have warning
severity. [web/package.json](web/package.json) invokes Spectral without making
warnings fatal. These mandatory declarations therefore are not enforced by this
lint command alone. This is not a claim that every other CI check would miss an
authentication-contract regression. Promote mandatory rules to errors or introduce
an explicit, scoped allowance mechanism for accepted warnings.

**Accessibility:** [accessibility.spec.ts](e2e/tests/accessibility.spec.ts) disables
`color-contrast` globally. This is an intentional existing design waiver, also
described in [its scenario](e2e/scenarios/accessibility.md), but it hides new contrast
regressions as well. If revisited, constrain legacy allowances to known surfaces and
make new violations fail. A theme correction would be application work, not a
documentation-only change. Do not silently reopen the accepted design decision.

### DQ-06 — Instruction volume makes authoritative rules hard to identify

**Observation:** A whitespace-based count found approximately 35,000 words in
CLAUDE.md and its six imported cross-cutting documents, rising to 52,000 when
web/CLAUDE.md is included. Current requirements, historical explanations, exceptions,
and implementation details frequently share long paragraphs.

**Risk:** Important rules compete with repeated or historical material. Duplicated
descriptions can drift independently, as the locking description demonstrates.

**Recommendation:** Keep AGENTS.md and CLAUDE.md as concise entry points with a
task-to-document reading map. Give each normative rule one canonical home. Preserve
useful rationale and historical audits in linked records, clearly dated. Check
active Markdown links, Claude imports, and reference-example paths mechanically;
historical references should be pinned or exempted deliberately.

The measured word counts indicate maintenance and navigation cost; they are not
evidence of a specific model context failure and should not become a word-count gate.

### DQ-07 — Broad copy instructions and textual DRY criteria are insufficient

**Observation:** The feature template in [CLAUDE.md](CLAUDE.md) says to copy `catalog/`
and extract when implementations are byte-identical. Existing shared infrastructure
is valuable, but a whole feature contains policies that do not apply to every new
resource.

**Recommendation:** Replace the broad instruction with a small reference catalog:

| Task | Required properties of the working example |
| --- | --- |
| Paginated list | Shared predicates, filtering, count/rows consistency |
| Owned-resource mutation | Authorization/disclosure before semantic validation |
| Atomic multi-table write | Transaction owner, lock order, rollback regression |
| Bulk import | Per-document transaction boundary, resource budgets, truthful outcomes |
| Frontend server state | Cache identity, invalidation, cancellation, error presentation |

For each entry, link exact functions and relevant tests, explain when it applies,
and identify exceptions. Avoid maintaining uncompiled copies of production examples.

Use semantic reuse as the criterion: share behavior representing the same policy
that must evolve together; keep differing policies separate. Do not introduce a
generic CRUD base class or interface merely to reduce matching lines.

### DQ-08 — A cancellation rule must account for the transport deadline

**Observation:** [web/CLAUDE.md](web/CLAUDE.md) describes a transport timeout.
In [http.ts](web/src/api/http.ts), `sendWithToken` passes
`{ signal: timeoutSignal(), ...init, headers }` to `fetch`. A caller-supplied
`init.signal` replaces the timeout signal.

**Risk:** Adding caller cancellation throughout read queries without adjusting the
transport can remove the advertised deadline from those requests.

**Recommendation:** Specify ownership of requests and late results, propagate
cancellation for obsolete reads, and preserve the transport deadline when composing
signals. Review mutation cancellation separately: abandoning a response does not
establish that the server abandoned its write. Adopting this rule requires focused
code changes and tests; it is not an invariant the current docs should claim already
holds everywhere.

## Additional prescriptions proposed for review

These are proposed improvements, not confirmed application defects. Check existing
canonical guidance before adding them, and generalize rather than duplicate rules.

1. **Atomic use-case ownership:** Name one outer transaction owner. Helpers that
   participate in the atomic write must not silently open independent transactions.
   Declare lock ordering and prove rollback across the affected records. Preserve
   existing per-document import transactions; do not replace them with a batch-wide
   transaction or introduce global locks as a default.
2. **Budget ownership:** For user-controlled bulk work, identify count, byte, work,
   and concurrency limits; enforce them before expensive materialization and define
   the exhaustion result. Extend the existing bounded-processing conventions rather
   than implying that v2.4.0's entity budgets are missing.
3. **Frontend state ownership:** Identify the account, world, view, or resource that
   owns state and specify invalidation and late-result behavior. Prefer feature-owned
   query-key factories shared by queries, cache writes, and invalidation, preserving
   intentional broad-prefix invalidations.
4. **Complexity:** Pages orchestrate UI; domain decisions belong in focused functions
   or services. Existing [ESLint limits](web/eslint.config.js) are generous backstops,
   not design targets. Consider production/test distinctions and incremental
   ratcheting, rather than arbitrary tighter limits or unrelated refactors to appease
   a number. Extract responsibilities, not just blocks of lines.
5. **Regression quality:** A regression should fail against the defect and assert
   observable behavior or an important invariant. Global coverage percentages do not
   prove a changed API operation has useful success, rejection, and authorization
   coverage. The OpenAPI execution report is explicitly a report, not a gate; consider
   a meaningful operation-coverage ratchet without demanding artificial tests for
   every declared `500` response.
6. **Scoped exceptions:** Record scope, rationale, compensating checks, and a review
   trigger. A permanent compatibility exception can be valid; blanket waivers that
   automatically shield future code are weaker. Do not require expiry dates for every
   durable design choice or silently reactivate parked work.
7. **Shared-workspace scope:** Verify worktree, branch, and staged diff before a commit;
   stage only task-owned changes. Use separate worktrees for independent concurrent
   changes and explicit file ownership for cooperating agents. The recent mixed
   commit in this session is a process motivation, not a newly discovered code defect.
   Preserve existing user authorization without adding repetitive approval rituals.

## Suggested format for important rules

Use a compact structure for a small number of cross-cutting rules, following the
useful stable-ID precedent in the API guidelines:

- **Applies when:** the concrete trigger or kind of change.
- **Requirement:** an observable MUST/MUST NOT, with the invariant it protects.
- **Reference:** canonical implementation and relevant tests.
- **Enforcement:** named automated gate or an explicit manual review check.
- **Exception:** bounded scope, rationale, and review trigger, if applicable.

Do not replicate this template for every descriptive paragraph. Avoid a generic
SOLID essay, speculative framework changes, another enormous checklist, or coverage
targets raised merely for appearance.

## Suggested sequencing and acceptance criteria

| Batch | Scope | Acceptance criteria |
| --- | --- | --- |
| A | Documentation corrections and consolidation | Contradictions resolved; canonical rule owners and task-specific references are clear; critical requirements remain discoverable; no runtime/API behavior changes |
| B | Enforcement of existing promises | Each claimed gate matches actual check behavior; exceptions are explicit; meaningful negative probes show the strengthened checks fail; GitHub settings change only if parked work is resumed |
| C | New architectural prescriptions and focused compliance work | Identify concrete affected paths first; implement narrowly; regressions prove transaction, budget, async-state, or cache invariants as applicable |

Documentation edits alone cannot complete batches B and C. They may involve scripts,
CI settings, tests, and application code. Keep those changes separately reviewable.

## Evidence and limitations

- The review used independent backend/API, frontend, and quality-gate documentation
  inspections, followed by coordinator validation and removal of unsupported claims.
- Read-only checks included repository status, source/configuration inspection,
  documentation counts, relative Markdown link inspection, and GitHub branch-rule
  API reads. A reviewer ran the existing Spectral lint successfully, with warnings
  and hints; that result does not establish enforcement of warning-level rules.
- A suspected non-strict TypeScript configuration was rejected: although `strict`
  is not explicit in the app config, the installed TypeScript 6.0.3 compiler computes
  `noImplicitAny` and `strictNullChecks` as enabled. Do not report that as a defect.
- This was not a fresh full application audit. No full backend, frontend, or browser
  suite was run for this documentation review, and no runtime defect reproduction
  was performed for the proposed architectural improvements.
- External branch rules can change independently of this commit. Recheck them before
  acting. Documentation references should likewise be revalidated against the then
  current branch.

## Questions for Claude's review

1. Which findings survive an independent check of the cited implementation and
   authoritative guidance? Identify counterevidence rather than assuming agreement.
2. Which proposed rules already exist in a canonical document and need a link or
   clearer wording instead of another copy?
3. Does the proposed reference catalog preserve intentional differences between
   Backstage, Port, owned resources, imports, and high-frequency view state?
4. Which prescriptions can be enforced cheaply and reliably, and which should remain
   focused review checks? Avoid brittle checks that reward superficial compliance.
5. Can batch A be delivered without erasing rationale or weakening security,
   transaction, resource-budget, API, or test-isolation guarantees?

Reviewing this report does not authorize implementing its recommendations, changing
parked decisions, committing, pushing, merging, or deploying.
