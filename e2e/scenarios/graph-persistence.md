# Reliable per-user graph layout persistence

- **Spec**: [tests/graph-persistence.spec.ts](../tests/graph-persistence.spec.ts)
- **Actors**: the seed admin creates and cleans up one throwaway regular user; the throwaway
  user owns every layout interaction
- **Owns** (exclusive server-side state): the throwaway user's account and graph-layout
  document; no catalog files or shared registry state

## Scenario: layout loading and serialized retries preserve the latest full document

1. The admin creates a throwaway user and seeds that user's layout with an Auto mode plus an
   off-screen node position and collapsed ID.
   - *Expected*: the layout exists only for the throwaway account; the seed admin's layout is
     never read or changed.
2. The throwaway user opens Graph while the real initial layout response is held.
   - *Expected*: a named loading status is visible, both layout modes are disabled, and no PUT
     can start before the complete baseline arrives.
3. The response is released, then a fresh load receives one controlled failure.
   - *Expected*: the page shows the safe layout-load error, keeps controls disabled, and Retry
     restores the saved Auto baseline from the real server.
4. The user switches to Manual while its successful PUT response is held, then chooses Auto and
   Manual again before that first acknowledgement is released.
   - *Expected*: the UI follows the latest choice, only one request is in flight, and after it
     finishes exactly one coalesced trailing PUT sends Manual plus the off-screen position and
     collapsed ID from the full baseline.
5. The user switches to Auto and that save receives one controlled failure, then chooses Manual
   and resets positions while the error remains visible.
   - *Expected*: Manual with empty positions becomes the latest local state without starting
     another PUT, a safe inline save error offers Retry, and the failure state is captured as a
     Playwright attachment for visual inspection.
6. The user retries the save and reloads Graph.
   - *Expected*: Retry sends the latest Manual document with positions cleared and the off-screen
     collapsed ID preserved, rather than replaying the failed Auto request; the API returns that
     same server truth and reload restores Manual.
7. The retained administrator session deletes the throwaway user in cleanup.
   - *Expected*: the account and its private layout document do not survive the journey.

## Not covered here (and why)

Real node folding and canvas dragging remain in `render.spec.ts`; this journey uses mode controls
and invisible stored IDs so its ordering assertions do not depend on shared catalog fixtures or
React Flow geometry.
