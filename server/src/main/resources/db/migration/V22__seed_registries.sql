-- Seeds all six ADMIN-curated registries with the reference workspace's curation, so a
-- freshly built environment (compose, the Testcontainers suite, e2e, k8s) starts with the
-- vocabulary an admin would otherwise re-enter by hand. V8/V15/V16 seeded only Backstage's
-- well-known values; labels, tag categories and annotation keys had no seed at all, so an
-- empty registry meant no file could carry any of them.
--
-- Every statement is an idempotent upsert whose conflict target names the table's PARTIAL
-- unique index over active rows, spelled with that index's own predicate
-- (`marked_as_deleted = false` for V7's dictionary index, `NOT marked_as_deleted` for
-- V10/V11/V14/V17's — and the LOWER(...) expression for the three case-insensitive ones).
-- The three registries V8/V15/V16 already seeded take DO UPDATE, because a seed declares the
-- intended STATE and those rows exist: the VALUES list below IS the curation, and DO NOTHING
-- alone could never bring V15's rows to it. The three that had no seed at all take DO
-- NOTHING, so an admin who curated them before upgrading keeps their work.
--
-- What survives an upgrade, then: an admin's own extra ROWS everywhere (a namespace, a
-- lifecycle, a label they added), and every edit to labels/tag categories/annotation keys.
-- What does not: a re-ordering of the seeded namespaces/lifecycles, and — the one with
-- teeth — extra TYPES an admin added inside a seeded kind's row, since the type list is
-- replaced wholesale. Stored files carrying such a type become strict-invalid on their next
-- save (the Errors report names them), which is the same posture as an admin removing the
-- value by hand.
--
-- Nothing here is special-cased afterwards: every row is ordinary and an admin may edit,
-- reorder or delete it (removing a value makes files carrying it strict-invalid on their
-- next save — a deliberate admin choice, the V15/V16 posture).

-- 1. Namespaces. V8 seeded `default` (position 0) and V9 flagged it as the blank-namespace
-- resolution target; `external` joins it as an ordinary unflagged entry. The upsert touches
-- `position` only, so the default flag stays on `default` (and
-- uq_dictionary_entries_default_active permits at most one anyway).
INSERT INTO dictionary_entries (dictionary, position, value) VALUES
    ('NAMESPACE', 0, 'default'),
    ('NAMESPACE', 1, 'external')
ON CONFLICT (dictionary, value) WHERE marked_as_deleted = false
DO UPDATE SET position = EXCLUDED.position;

-- 2. Lifecycles. `sunsetting` joins V16's three, and the positions are rewritten so the
-- stored order reads as the real progression experimental → production → sunsetting →
-- deprecated (V16 left `deprecated` at 2, so it moves to 3). NO is_default on any of them —
-- validateDictionaryUpdate rejects flags on this dictionary.
INSERT INTO dictionary_entries (dictionary, position, value) VALUES
    ('LIFECYCLE', 0, 'experimental'),
    ('LIFECYCLE', 1, 'production'),
    ('LIFECYCLE', 2, 'sunsetting'),
    ('LIFECYCLE', 3, 'deprecated')
ON CONFLICT (dictionary, value) WHERE marked_as_deleted = false
DO UPDATE SET position = EXCLUDED.position;

-- 3. Per-kind type dictionaries. DO UPDATE brings V15's existing rows to the curated lists
-- and the INSERT half covers a kind whose row an admin soft-deleted (a soft-deleted row
-- frees its kind, V14's index being over active rows only). User stays absent on purpose —
-- its spec has no type field, and EntityTypesTest pins that it must never hold a dictionary.
INSERT INTO entity_types (kind, types) VALUES
    ('Component', '["service","website","library","job","data-pipeline"]'),
    ('API', '["openapi","asyncapi","graphql","grpc","web-sockets"]'),
    ('System', '["product","capability"]'),
    ('Domain', '["core-value","auxiliary","generic","tech-foundations"]'),
    ('Resource', '["database","message-broker","transaction-log","analytical-database","cache"]'),
    ('Group', '["team","org-unit","org-division"]')
ON CONFLICT (kind) WHERE NOT marked_as_deleted
DO UPDATE SET types = EXCLUDED.types;

-- 4. The label registry (V10's table, previously unseeded). One row = one allowed
-- metadata.labels key with its CLOSED value list and the kinds it applies to. Every value
-- satisfies the entity-name grammar (validateLabelRequest checks values, not just keys), and
-- allowed_kinds is written in SUPPORTED_KINDS order so the next admin PUT is a no-op.
INSERT INTO labels ("key", allowed_values, allowed_kinds) VALUES
    ('criticality-tier', '["critical","non-critical"]', '["System"]'),
    ('data-classification', '["public","internal","confidential","restricted"]', '["Resource"]'),
    ('exposure', '["public","partner","internal"]', '["Component","API","System","Resource"]'),
    ('gdpr', '["yes","no"]', '["Resource"]'),
    ('hosting-model', '["public-cloud","on-premise"]', '["Component","API","System","Resource"]'),
    ('pci-dss', '["yes","no"]', '["Resource"]'),
    ('support-mode', '["business-hours","business-days","best-effort","24-7"]', '["System"]'),
    ('technology-status', '["white-list","black-list","grey-zone"]', '["Component","Resource"]')
ON CONFLICT (LOWER("key")) WHERE NOT marked_as_deleted DO NOTHING;

-- 5. The tag categories (V11's table, previously unseeded) — the INTERNAL grouping concept.
-- The one-category-per-tag invariant has NO database backstop (tags live inside the JSON
-- array; it is enforced service-side in TagCategoryService.requireTagsUnclaimed), so these
-- four tag lists are deliberately disjoint — keep them that way when extending this seed.
INSERT INTO tag_categories ("name", tags, allowed_kinds) VALUES
    ('Languages', '["java","python","php","javascript","typescript"]', '["Component"]'),
    ('Framework', '["spring-boot","quarkus","micronaut","symphony","react","vue"]', '["Component"]'),
    ('Database', '["postgresql","redis","clickhouse"]', '["Resource"]'),
    ('Events', '["kafka","rabbitmq","activemq"]', '["Resource"]')
ON CONFLICT (LOWER("name")) WHERE NOT marked_as_deleted DO NOTHING;

-- 6. The annotation-key registry (V17's table, previously unseeded) — keys only, annotation
-- VALUES stay free strings. None of these is a SERVER-WRITTEN key: validateAnnotationKeyRequest
-- rejects backstage.io/managed-by-location, backstage.io/managed-by-origin-location and
-- backstage.io/orphan, so those must never be seeded here.
INSERT INTO annotation_keys ("key", allowed_kinds) VALUES
    ('backstage.io/source-location', '["Component","API","System"]'),
    ('backstage.io/techdocs-ref', '["Component","API","System","Domain","Resource"]'),
    ('backstage.io/kubernetes-id', '["Component","System","Resource"]'),
    ('backstage.io/kubernetes-label-selector', '["Component","System","Resource"]')
ON CONFLICT (LOWER("key")) WHERE NOT marked_as_deleted DO NOTHING;
