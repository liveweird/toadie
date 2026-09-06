# Generated-password reveal readiness

- **Spec**: [tests/password-reveal-readiness.spec.ts](../tests/password-reveal-readiness.spec.ts)
- **Actors**: the seed administrator; one throwaway regular user created through the UI
- **Owns** (exclusive server-side state): its unique `e2e-reveal-reader-*` throwaway user,
  deleted directly with the retained administrator authorization in `finally`

## Scenario: generated-password reads reject a click that leaves the password masked

1. The administrator opens the real user-creation form and supplies a unique name and email.
   - *Expected*: creation returns `201` with the throwaway user's id, and the genuine, ready
     **User created** dialog shows the password masked.
2. A one-shot browser capture listener suppresses the next real **Show password** click before
   the application receives it, matching the confirmed failure condition.
   - *Expected*: the shared helper rejects while waiting for **Hide password** rather than
     returning the mask; exactly one click was suppressed and **Show password** remains unpressed.
3. With the one-shot listener automatically removed, the test deliberately invokes the helper
   again and closes the dialog.
   - *Expected*: the ordinary reveal reaches **Hide password** pressed and returns a non-empty,
     unmasked value.
4. The new user signs in with the captured password.
   - *Expected*: the authenticated account menu appears, proving the complete generated value
     was read. The throwaway user is deleted with retained administrator authorization in
     `finally`.

## Not covered here (and why)

- The broader user lifecycle remains in `users.spec.ts`. Modal entrance behavior is pinned by
  `dialog-readiness.spec.ts`; this regression isolates the confirmed masked-read failure without
  changing product animation or mocking the API.
