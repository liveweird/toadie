# File history (the per-file change trail on the editor)

- **Spec**: [tests/history.spec.ts](../tests/history.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) — an ordinary user here (the
  history carries no admin surface: whoever may read a file may read its history, which in
  this shared workspace is everyone)
- **Owns** (exclusive server-side state): one throwaway unique-named Component file
  (`e2e-hist-…`, deleted at the end) and the history events it mints — a history is per file,
  so nothing this run asserts can be reached by another run

## Scenario: a file's history records its creation and each later edit, field by field

1. The admin signs in and creates a minimal Component (name, type, lifecycle, owner).
2. They open the file's editor.
   - *Expected*: below the form and the YAML preview there is a **History** section holding
     exactly one entry, **"File created."**, with the acting user's name and the timestamp.
3. They set a Title and a Description and save.
4. They reopen the editor.
   - *Expected*: the newest entry reads **"File updated: Title, Description."** — the sentence
     names both changed fields — and under it a single before/after line, **"Title: set to
     Checkout service"**. The description gets NO line of its own: free text is recorded as
     the bare fact that it changed, never as its content.
   - *Expected*: the creation entry is still there, below the edit (newest first).
5. They delete the file from the filtered Files list.

## Not covered here (and why)

- **Sync and import events, the params vocabulary, paging, the newest-first ordering with its
  same-instant tiebreaker, the 404 on a deleted file, and the no-op save that records
  nothing** — pinned by the server suite (`CatalogFileHistoryTest`) and the pure diff matrix
  (`CatalogFileEventsTest`); the SPA's rendering of every event kind, the unknown-kind
  fallback and the failed-load alert are pinned by `CatalogFileHistory.test.tsx`. E2E sticks
  to the one journey that proves the wiring end to end.
- **A deleted file's trail.** The deletion event is minted but the history of a deleted file
  is a 404 by design, so there is nothing to see through the UI.
