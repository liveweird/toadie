-- Registers the well-known `backstage.io/definition-at-location` annotation key (API only) for
-- the 2.31.0 export of a Port API's `definition_url` property (the default conversion mapping,
-- `sample-data/port/commerce-payments/mapping/backstage.json`, writes it as `url:<address>`). Like V43 this
-- is a data-only seed adjustment with its OWN migration rather than an edit of V22's immutable
-- bytes (Flyway/MigrationChecksumTest), and it takes V43's posture over V22's: the INSERT checks
-- `NOT EXISTS` over ALL rows with that LOWER(key), soft-deleted included, so a key an admin
-- registered by hand (with whatever kinds), edited, or deliberately deleted is left exactly as
-- it is and is never resurrected — unlike V22's `ON CONFLICT DO NOTHING`, whose partial-index
-- target let a soft-deleted twin be re-created active.
-- Idempotent and order-independent: a second run finds the row and inserts nothing.
-- `allowed_kinds` is read back exclusively through `Json.decodeFromString`
-- (annotations/AnnotationKeyService.kt, catalog/CatalogRegistryReader.kt) and never
-- string-compared. The key is not one of the server-written `backstage.io/*` keys the registry
-- refuses (managed-by-location, managed-by-origin-location, orphan).
INSERT INTO annotation_keys ("key", allowed_kinds)
SELECT v."key", v.allowed_kinds
FROM (VALUES
    ('backstage.io/definition-at-location', '["API"]')
) AS v("key", allowed_kinds)
WHERE NOT EXISTS (SELECT 1 FROM annotation_keys a WHERE LOWER(a."key") = LOWER(v."key"));
