# Self-service password reset

- **Spec**: [tests/password-reset.spec.ts](../tests/password-reset.spec.ts)
- **Actors**: an anonymous visitor on the reset form; the seed administrator
  (`admin@toadie.local`) only to create/delete the throwaway account
- **Owns** (exclusive server-side state): one throwaway user account (email carries the
  `e2e` marker), deleted at the end. The reset requests use unique per-run emails, so the
  in-memory per-email throttle never collides across runs; the dev stack lifts the per-IP
  reset bucket (100/min, the login-bucket idiom), so back-to-back runs never trip it. Delivered messages remain in the Mailpit catcher —
  that is what a mail catcher is for.

## Scenario: the forgot-password link leads to the reset form; unknown emails get the neutral answer

1. A visitor on the login page follows the **Forgot password?** link.
   - *Expected*: the `/reset-password` form renders (lazy route).
2. They submit a unique unknown email address.
   - *Expected*: the neutral confirmation ("if an account with this address exists…") —
     account existence is unobservable.
3. They submit another unique address twice in a row.
   - *Expected*: the first answer is the same neutral confirmation; the second shows the
     throttle message (one request per minute per address) — uniformly, even though the
     account does not exist.

## Scenario: a reset link preserves the old password until confirmation and cannot be reused

*Skips itself when Mailpit (`E2E_MAILPIT_URL`, default `http://localhost:8026`) is
unreachable locally — the email roundtrip cannot be observed on a log-transport dev stack.
In CI it fails instead of skipping; the disposable stack must provide Mailpit.*

1. The admin creates a throwaway user through the real UI (capturing the one-time revealed
   password) and signs out.
2. A visitor submits the throwaway user's email on the reset form.
   - *Expected*: neutral confirmation; asynchronously, a "Reset your Toadie password"
     email carries a single-use link with a 43-character token in its fragment, not a password.
3. They sign in with the ORIGINAL password and open the emailed link while still signed in.
   - *Expected*: the password still works; the public confirmation form renders, strips the
     fragment from history, and does not revoke the session (the regular user's protected
     users-list request is still 403, not 401).
4. They choose and confirm a new password.
   - *Expected*: sign-in-again confirmation; the old session now returns 401, the chosen
     password signs in normally, and the original password returns "Invalid email or password".
5. They reopen the same link and submit again.
   - *Expected*: invalid/expired/used-link message and a link to request another reset.
6. The admin deletes the throwaway account, including on failure via `finally` cleanup.

## Not covered here (and why)

- **Configuration/mail 503s, malformed-input 400s, expiry/concurrency/epoch changes,
  delivery and notification failures, pending MFA invalidation** — server-tested
  (`PasswordResetTest`, `PasswordResetConfirmationTest`, `PasswordResetServiceTest`);
  the SPA's per-status messages and validation are unit-tested on both reset pages.
- **The per-IP reset bucket** — lifted on dev stacks; exercising the production 5/min from
  one host would poison the rest of the run (the login-lockout precedent).
