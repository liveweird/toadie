# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: the seed administrator (`admin@toadie.local`) for the authenticated pages; an
  anonymous visitor for the login screen
- **Owns** (exclusive server-side state): nothing — read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for all of them.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` waived theme-wide) reports
     zero violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags and waiver) reports zero violations.

## Scenario: reset confirmation and missing-link states have no WCAG A/AA violations

1. An anonymous visitor opens the confirmation page with a syntactically valid, unissued token.
   - *Expected*: the choose-password form renders and the URL fragment disappears; axe reports
     zero violations (the same theme-wide contrast waiver applies).
2. They reload the stripped URL.
   - *Expected*: the missing-link recovery state offers another reset request and passes axe.
     No token is issued or consumed, and no server records are created.

## Not covered here (and why)

- **Interactive journeys** (modals mid-flight, drag interactions) — apart from the explicit
  report-loading regression below, deeper audits are a deliberate, separate pass.
- **color-contrast** — a conscious theme-wide waiver (dimmed text by design), not a backlog.

## Scenario: the Errors loading state has no WCAG A/AA violations

1. The admin opens Errors while its real report request is held by the browser test.
   - *Expected*: the named loading indicator is visible and an axe scan reports zero violations.
2. The request is released and the real report finishes loading.
   - *Expected*: the loading indicator disappears and a second axe scan reports zero violations.
