-- Seeds the HIERARCHY dictionary — the identifiers of the parallel entity hierarchies a
-- blueprint may point one of its single relations at (`hierarchyRelations`, next release;
-- this dictionary is purely additive today, nothing consumes it yet) — with the one
-- hierarchy that already exists in practice: `composition`, the containment tree the
-- pre-1.32 single `hierarchyRelation` always described. The V8 idiom: idempotent via the
-- conflict target naming V7's partial unique index over active rows. NO default flag —
-- hierarchies have no blank-resolution concept (validateDictionaryUpdate rejects flags on
-- this dictionary). The next migration backfills every existing `hierarchyRelation` pointer
-- into this seeded `composition` entry.
INSERT INTO dictionary_entries (dictionary, position, value) VALUES
    ('HIERARCHY', 0, 'composition')
ON CONFLICT (dictionary, value) WHERE marked_as_deleted = false DO NOTHING;
