# Entity history (the per-entity change trail on the editor, 2.37.0)

- **Spec**: [tests/entity-history.spec.ts](../tests/entity-history.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (entities
  carry no admin gate, and whoever may read an entity may read its history)
- **Owns** (exclusive server-side state): one throwaway blueprint named `e2e-ehist-…` (an enum
  property "Stage" and a markdown property "Notes") and one entity of it, both created and
  deleted by the one scenario, plus the history events that entity mints — a history is per
  entity, so nothing this run asserts can be reached by another run

## Scenario: an entity's history records its creation and each later edit, field by field

1. The admin signs in and, via the API, creates the throwaway blueprint and one entity of it
   (title, Stage `alpha`, Notes text).
2. They open the entity's editor (`/entities/<id>/edit`).
   - *Expected*: below the form there is a **History** section holding **"Entity created."**.
3. They change the Title, pick Stage `beta`, rewrite the Notes and save.
   - *Expected*: the save answers `204`.
4. They reopen the editor.
   - *Expected*: the newest entry reads **"Entity updated: Title, Stage, Notes."** — the
     sentence names the changed fields by the blueprint's property titles — with a
     **"Title: … → …"** line and a **"Stage: alpha → beta"** line under it.
   - *Expected*: the Notes get NO line of their own, and neither the old nor the new markdown
     text appears anywhere in the History section: markdown (like yaml and proto) is recorded
     as the bare fact that it changed, never as its content.
   - *Expected*: the creation entry is still there, below the edit (newest first).
5. The spec deletes the entity, then the blueprint (in a `finally`, so a failed assertion
   leaves nothing behind).

## Not covered here (and why)

- **The `_team` rename cascade, import, sync and MCP-upsert events, the params vocabulary,
  paging, the newest-first same-instant tiebreaker, the 404 on a deleted entity and the no-op
  save that records nothing** — pinned by the server suite (`EntityHistoryTest`, `EntityEventsTest`) against
  owned fixtures; the SPA's rendering of every event kind, the unknown-kind fallback and the
  failed-load alert are pinned by `EntityHistory.test.tsx`. E2E sticks to the one journey that
  proves the wiring end to end.
- **Rollback when history insertion fails and concurrent before/after diffs.** Backend tests
  inject PostgreSQL failures and observe lock contention; browser timing cannot prove
  transaction boundaries.
