-- Seeds the namespaces dictionary with Backstage's built-in `default` namespace, so a
-- freshly built database accepts the blank-namespace catalog files the UI produces out of
-- the box. Idempotent via the V3 seed idiom: the conflict target names V7's partial unique
-- index over active rows, so a database that somehow already holds an active `default` is
-- left alone. `default` is an ordinary entry with no special-case protection: removing it
-- later makes blank/`default` namespaces rejected, a deliberate admin choice.
INSERT INTO dictionary_entries (dictionary, position, value) VALUES
    ('NAMESPACE', 0, 'default')
ON CONFLICT (dictionary, value) WHERE marked_as_deleted = false DO NOTHING;
