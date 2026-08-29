-- Seeds the LIFECYCLE dictionary — the GLOBAL allowlist every catalog-file write's
-- spec.lifecycle is validated against (one list for all lifecycle-bearing kinds) — with the
-- descriptor reference's well-known values, so a freshly built database accepts the
-- documents the UI (and the e2e suite) produces out of the box. The V8 idiom: idempotent
-- via the conflict target naming V7's partial unique index over active rows. NO default
-- flag — lifecycles have no blank-resolution concept (validateDictionaryUpdate rejects
-- flags on this dictionary). Ordinary entries: admins may reorder, rename, or remove them
-- (a removed value makes files carrying it strict-invalid on their next save — deliberate).
INSERT INTO dictionary_entries (dictionary, position, value) VALUES
    ('LIFECYCLE', 0, 'experimental'),
    ('LIFECYCLE', 1, 'production'),
    ('LIFECYCLE', 2, 'deprecated')
ON CONFLICT (dictionary, value) WHERE marked_as_deleted = false DO NOTHING;
