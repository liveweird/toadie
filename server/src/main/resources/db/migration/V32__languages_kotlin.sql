-- Adds "kotlin" to the seeded "Languages" tag category (V22, `tag_categories.tags` — a JSON
-- array in TEXT). Every future data-only seed adjustment gets its OWN migration rather than
-- editing V22's immutable bytes (Flyway/MigrationChecksumTest). This is the conscious decision
-- V22's own doc paragraph asked the next seed to make, taken in the OPPOSITE direction of its
-- INSERT-half posture:
--   * an admin's soft-DELETION of the "Languages" row is NOT undone — the predicate requires
--     an ACTIVE row, so a removed category is never resurrected and never re-created here;
--   * an admin who already MOVED "kotlin" into a different active category is respected — the
--     one-category-per-tag invariant has no database backstop (TagCategoryService enforces it
--     service-side), so this statement checks every other active category first and is a no-op
--     if any of them already holds "kotlin";
--   * an admin's own additions/removals to "Languages" otherwise SURVIVE — this appends one
--     value to the existing JSON array (`tags::jsonb || '["kotlin"]'::jsonb`), it never replaces
--     the column the way V22's seed does;
--   * idempotent and order-independent: re-running finds "kotlin" already present via the
--     `? 'kotlin'` containment check and updates nothing.
-- `tags` is read back exclusively through `Json.decodeFromString` (TagCategoryService.kt,
-- kotlinx serialization) and never string-compared, so this migration's PostgreSQL `jsonb`
-- round-trip re-serializing the array with spaces (`["a", "b"]` vs. the service's compact
-- `["a","b"]`) is immaterial — no code path compares the raw column text.
UPDATE tag_categories
SET tags = (tags::jsonb || '["kotlin"]'::jsonb)::text
WHERE LOWER(name) = 'languages'
  AND NOT marked_as_deleted
  AND NOT (tags::jsonb ? 'kotlin')
  AND NOT EXISTS (
      SELECT 1 FROM tag_categories o
      WHERE NOT o.marked_as_deleted
        AND o.tags::jsonb ? 'kotlin'
  );
