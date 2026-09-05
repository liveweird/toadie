# Dialog entrance readiness

- **Spec**: [tests/dialog-readiness.spec.ts](../tests/dialog-readiness.spec.ts)
- **Actors**: the seed administrator opening an unsaved lens editor
- **Owns** (exclusive server-side state): nothing — read-only; the editor is cancelled

## Scenario: dialog actions wait for the entrance transition to finish

1. The admin opens Files and chooses **Save as new lens…**.
   - The test temporarily holds the real modal's computed opacity at zero with a browser-only
     style; API responses and production animation settings are unchanged.
2. The test asks the shared `readyDialog` helper for the editor while it is held entering.
   - *Expected*: Playwright considers the modal visible, but the readiness helper does NOT
     resolve; a browser round-trip confirms the helper still waits.
3. The entrance hold is removed in `finally`.
   - *Expected*: the helper resolves only once computed opacity reaches one.
4. The admin cancels the unsaved editor.
   - *Expected*: the modal disappears and no lens is created.

## Not covered here (and why)

- The whole lens lifecycle is covered by `lenses.spec.ts`; this regression isolates the
  difference between DOM visibility and readiness without relying on a random timing failure.
