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

## Not covered here (and why)

- **Interactive journeys** (modals mid-flight, drag interactions) — the smoke scans settled
  pages; deeper audits are a deliberate, separate pass.
- **color-contrast** — a conscious theme-wide waiver (dimmed text by design), not a backlog.
