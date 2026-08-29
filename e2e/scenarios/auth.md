# Login and logout

- **Spec**: [tests/auth.spec.ts](../tests/auth.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`)
- **Owns** (exclusive server-side state): nothing — read-only (sessions only; no seeded account
  is ever mutated)

## Scenario: admin can log in and log out

1. The admin signs in through the real login form — email, password, "Sign in".
   - *Expected*: the app shell is up — the **Hierarchy** heading and the header Logout button are
     visible.
2. The admin clicks **Logout** in the header.
   - *Expected*: they are back on the login screen — the **Sign in** button and the
     "You've been signed out." banner are visible.

## Scenario: invalid credentials are rejected

1. A visitor submits the login form with the admin's email and a wrong password.
   - *Expected*: the "Invalid email or password" rejection is shown.

## Scenario: a deep link is guarded and lands back after signing in

1. An anonymous visitor opens a deep app URL.
   - *Expected*: they are bounced to the sign-in form.
2. They sign in with the admin's credentials.
   - *Expected*: the app returns to the requested path inside the shell — for an unknown path
     that is the **Page not found** page, with the header Logout button visible (never a blank
     document).

## Not covered here (and why)

- **Login lockout (429)** — repeated failed logins would lock the seeded account in the shared
  database and poison the rest of the run. Covered by `LoginThrottleTest` / `LoginLockoutTest`
  (server).
- **Token refresh / expiry** — needs clock control; covered by server tests and the
  `web/src/api/api.test.ts` unit tests.
