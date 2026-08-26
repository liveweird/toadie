-- Exactly one active entry per dictionary may be flagged as the DEFAULT — for NAMESPACE it
-- is what a blank/omitted catalog-file namespace resolves to (admin-chosen; flipping it
-- never rewrites stored files, which hold concrete namespaces). The partial unique index is
-- the DB backstop for "at most one active default"; the EXACTLY-one rule (for a non-empty
-- document) lives in validateDictionaryUpdate. Soft-deleted rows keep their stale flag like
-- they keep their stale position — reads filter active, the index ignores them.
--
-- Edge: a database whose admin already removed the V8-seeded `default` entry gets NO
-- flagged default from the UPDATE below — blank-namespace writes then answer 400 until the
-- admin flags an entry on the Namespaces page. Deliberate: never resurrect a removed value.
ALTER TABLE dictionary_entries ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE dictionary_entries
SET is_default = TRUE
WHERE dictionary = 'NAMESPACE' AND value = 'default' AND marked_as_deleted = false;

CREATE UNIQUE INDEX uq_dictionary_entries_default_active
    ON dictionary_entries(dictionary)
    WHERE is_default AND marked_as_deleted = false;
