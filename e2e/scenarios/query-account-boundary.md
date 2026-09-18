# Entity query account boundary

- **Spec**: [tests/query-account-boundary.spec.ts](../tests/query-account-boundary.spec.ts)
- **Actors**: the seed administrator and two throwaway regular users
- **Owns** (exclusive server-side state): its two throwaway users and user A's private saved query;
  query view state is device-local

## Scenario: private entity query state stays with its account across logout and another login

1. The administrator creates two throwaway users. User A signs in, creates a private saved query,
   and selects it through the Entity graph's real saved-query picker.
   - *Expected*: user A's graph request carries the applied query, and the picker and editor show it.
2. User A signs out and user B signs in in the same browser without
   clearing localStorage.
   - *Expected*: user B's saved-query response excludes A's private query, a filtered graph request
     carries no entity query, the editor and picker are blank, and ownerless legacy keys are gone.
3. User B signs out and user A signs back in without clearing localStorage.
   - *Expected*: A's picker, editor, and outgoing graph query restore A's private state.
4. User A deletes the private query, then the administrator deletes both throwaway users.
