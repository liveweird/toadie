-- Seeds every type-bearing kind's dictionary with the descriptor reference's well-known
-- values, so a freshly built database accepts the documents the UI (and the e2e suite)
-- produces out of the box — the V8 namespace-seed idiom. Idempotent: the conflict target
-- names V14's partial unique index over active rows. Ordinary rows with no special-case
-- protection: an admin may edit or delete any of them (deleting one makes that kind's
-- spec.type unsaveable until re-defined — a deliberate admin choice). User is absent on
-- purpose — its spec has no type field.
INSERT INTO entity_types (kind, types) VALUES
    ('Component', '["service","website","library"]'),
    ('API', '["openapi","asyncapi","graphql","grpc"]'),
    ('System', '["product","service","feature-set"]'),
    ('Domain', '["product-area","product-group","bundle"]'),
    ('Resource', '["database","s3-bucket","kubernetes-cluster"]'),
    ('Group', '["team","business-unit","product-area","root"]')
ON CONFLICT (kind) WHERE NOT marked_as_deleted DO NOTHING;
