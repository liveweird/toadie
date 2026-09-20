-- Monotonic ontology revision (2.12.0): a single counter bumped by every committed blueprint,
-- entity, and `HIERARCHY`-dictionary write, so a GraphQL integration consumer paging blueprints
-- and entities across several requests can tell whether the ontology moved under it and restart
-- its scan. Change DETECTION only, never a snapshot — see `.claude/docs/integration-api.md`
-- "Ontology revision". A one-row hard-update table (the `user_disabled_features` exception
-- class): a pure counter, not a soft-deletable business entity, so there is nothing to soft-delete
-- and no history worth keeping.
CREATE TABLE ontology_revision (
    id       SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT   NOT NULL
);

INSERT INTO ontology_revision (id, revision) VALUES (1, 0);
