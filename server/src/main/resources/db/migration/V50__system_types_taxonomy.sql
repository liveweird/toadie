-- 2.35.0: re-curate the System type dictionary. The sample ontology's system.type moved from
-- product/capability to a six-value taxonomy (third parties are the type `external` now, the
-- `external` boolean/namespace rule is gone), and the default conversion mapping copies
-- system.type onto spec.type verbatim — the dictionary must hold the same list or every read of
-- the mapping reports VOCABULARY_DRIFT. V22's idiom: DO UPDATE replaces the ACTIVE row's list
-- wholesale once, when V50 applies to a database (admin additions included — the UI is a whole-document replace
-- too); the INSERT half covers a soft-deleted row. Only System changes; a stored catalog file
-- still typed product/capability keeps its document and shows a soft TYPE_NOT_ALLOWED finding
-- on the Errors report until it is edited (registry rules are soft on read, strict on the next
-- strict save).
INSERT INTO entity_types (kind, types) VALUES
    ('System', '["value-creation","shared-service","integration","data-and-reporting","infrastructure-and-platform","external"]')
ON CONFLICT (kind) WHERE NOT marked_as_deleted
DO UPDATE SET types = EXCLUDED.types;
