-- Re-curates V22's seeded label registry (`labels`) for the 2.17.0 regulatory flags: widens
-- "gdpr" and "pci-dss" from Resource-only to Component + API + Resource, and adds three keys
-- ("banking-outsourcing", "cash-flow-impact", "dora-cif"). Every future data-only seed
-- adjustment gets its OWN migration rather than editing V22's immutable bytes
-- (Flyway/MigrationChecksumTest). Like V32 this takes the two conscious decisions V22's own doc
-- paragraph asked the next seed to make:
--   * WIDENING touches ACTIVE rows only (`NOT marked_as_deleted`), so an admin's soft-deletion
--     of "gdpr"/"pci-dss" is NOT undone and the key is never resurrected; the kind list is
--     REBUILT as existing UNION new in canonical SUPPORTED_KINDS order (catalog/CatalogFile.kt),
--     so an admin's own extra kinds survive, the row's allowed_values are never touched, and
--     the next admin PUT of the row is a no-op;
--   * the INSERT checks `NOT EXISTS` over ALL rows with that LOWER(key), soft-deleted included
--     (the posture V22's paragraph recommended, unlike V22's own INSERT half), so a key an
--     admin created, edited or deliberately deleted is left exactly as it is.
-- Idempotent and order-independent: the widening skips a row that already holds both
-- "Component" and "API" (`?&` containment), the INSERT skips any key that already exists.
-- `allowed_kinds`/`allowed_values` are read back exclusively through `Json.decodeFromString`
-- (labels/LabelService.kt, catalog/CatalogRegistryReader.kt) and never string-compared, so this
-- migration's PostgreSQL `jsonb` round-trip re-serializing the array with spaces (`["a", "b"]`
-- vs. the service's compact `["a","b"]`) is immaterial.
UPDATE labels
SET allowed_kinds = (
    SELECT jsonb_agg(k ORDER BY array_position(ARRAY['Component','API','System','Domain','Resource','Group','User'], k), k)::text
    FROM (SELECT DISTINCT k
          FROM jsonb_array_elements_text(labels.allowed_kinds::jsonb || '["Component","API"]'::jsonb) AS t(k)) kinds
)
WHERE LOWER("key") IN ('gdpr', 'pci-dss')
  AND NOT marked_as_deleted
  AND NOT (allowed_kinds::jsonb ?& ARRAY['Component','API']);

INSERT INTO labels ("key", allowed_values, allowed_kinds)
SELECT v."key", v.allowed_values, v.allowed_kinds
FROM (VALUES
    ('banking-outsourcing', '["yes","no"]', '["Component","API","Resource"]'),
    ('cash-flow-impact',    '["yes","no"]', '["Component"]'),
    ('dora-cif',            '["yes","no"]', '["Component"]')
) AS v("key", allowed_values, allowed_kinds)
WHERE NOT EXISTS (SELECT 1 FROM labels l WHERE LOWER(l."key") = LOWER(v."key"));
