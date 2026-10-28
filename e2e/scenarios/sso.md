# Single sign-on (OIDC) through the stub identity provider

- **Spec**: [tests/sso.spec.ts](../tests/sso.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`); throwaway users whose emails carry
  the `e2e` marker; the stub identity provider (`e2e/stub-idp/server.mjs`) behind the label
  "Okta", which trusts whatever email and name are typed on its page
- **Owns** (exclusive server-side state): one throwaway account per scenario (created through the
  real UI, or created by the SSO sign-in itself), each deleted at the end. Nothing else is
  mutated; the seed admin is only used to look at and clean up those accounts.

*Needs the stack to run with the SSO overlay (`docker compose -f docker-compose.yaml -f
e2e/compose.sso.yaml up -d --build`, which the CI workflow does). Each scenario skips itself when
`GET /api/v1/login/options` answers `{"sso":null}`; under `CI` it fails instead, so a workflow
that loses the overlay cannot go green (the Mailpit rule in `mfa.md`).*

## Scenario: a new email signs in through SSO onto its deep link, without a password

1. A signed-out visitor opens the protected `/files`.
   - *Expected*: they land on the sign-in page, which leads with **Sign in with Okta**.
2. They press the button, and on the stub's page enter a fresh email and name, then **Continue**.
   - *Expected*: the browser comes back through the app's callback and lands on `/files`, signed
     in as a new account created from the identity (no second sign-in prompt).
3. They open the account menu.
   - *Expected*: there is no **Change password** item (the account has no password).
4. They sign out; the administrator signs in and filters the Users list to that name.
   - *Expected*: the row carries the **SSO** badge. The administrator then deletes the account.

## Scenario: an SSO sign-in links to the password account with the same email, which keeps its password

1. The administrator creates a throwaway user with a generated one-time password and signs out.
2. That email signs in through **Sign in with Okta** (the stub is given a different display name).
   - *Expected*: they are signed in as the existing account, and the account menu DOES offer
     **Change password** (a linked account keeps its password).
3. They sign out and sign in again with the one-time password.
   - *Expected*: the password login still works.
4. The administrator opens the Users list.
   - *Expected*: the row keeps the account's original name and now carries the **SSO** badge.
     The administrator deletes the account.

## Scenario: an administrator unlinks an SSO account, which links again on its next SSO sign-in

1. A fresh identity signs in through **Sign in with Okta** (creating a passwordless, linked
   account) and signs out.
2. The administrator opens the Users list, filters to that name, and from the row's **Operations**
   menu chooses **Unlink SSO identity for <name>**.
   - *Expected*: the confirm dialog "Unlink SSO identity?" carries the passwordless warning
     (nobody can sign in to the account until the person signs in through SSO again or a password
     is set).
3. The administrator presses **Unlink**.
   - *Expected*: `DELETE /api/v1/users/<id>/sso` answers 204, the dialog closes, the row loses its
     **SSO** badge and keeps **No password**.
4. The administrator signs out; the same identity goes through **Sign in with Okta** again.
   - *Expected*: they are signed in (the unlinked account is linked once more by its email).
5. The administrator looks at the Users list again.
   - *Expected*: the **SSO** badge is back on the row. The administrator deletes the account.

## Scenario: cancelling at the identity provider returns to the sign-in page with a message

1. From the sign-in page the visitor presses **Sign in with Okta** and chooses **Cancel** on the
   stub's page.
   - *Expected*: back on `/login`, a red alert says the sign-in was cancelled and offers the
     password; the password form is still there and nobody is signed in.

## Scenario: an administrator account is refused SSO and told to use its password

1. The administrator creates a throwaway user and promotes it to **Administrator**, then signs out.
2. That email goes through **Sign in with Okta**.
   - *Expected*: back on `/login`, a red alert says the account cannot sign in through single
     sign-on (an administrator is never linked or taken over by an identity-provider email);
     nobody is signed in.
3. The account signs in with its password; the seed administrator then deletes it.

## Not covered here (and why)

- **Every refusal and token defect** (bad signature, wrong audience/nonce/issuer, expired token,
  unverified email, replayed state or hand-off, MFA accounts, domain allow-list, the race on a
  first sign-in) - server-tested (`SsoLoginTest`, `IdTokenVerifierTest`, `SsoStoresTest`,
  `SsoConfigTest`); the stub never misbehaves on purpose.
- **Releasing a deleted account's identity** (`DELETE /api/v1/users/<id>/sso` against a soft-deleted
  row, the id taken from the `login.sso_failure` event), self-unlink and the not-linked 404 -
  server-tested (`SsoUnlinkTest`); the Users list never shows a deleted row, so there is no UI to
  drive.
- **A real Okta tenant** - the manual tenant check is in the run-stack runbook ("Enable Okta SSO").
- **The SPA's callback failure states** (invalid or reused link, throttled exchange) - unit-tested
  (`SsoCallback.test.tsx`, `Login.test.tsx`).
