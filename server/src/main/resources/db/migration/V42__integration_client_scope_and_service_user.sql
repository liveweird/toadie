-- Key scope (2.15.0): read = the GraphQL API and the MCP read tools; write = additionally the
-- MCP entity write tools. service_user_id pairs the client with its service account (V41), the
-- users row its entity writes are attributed to. Rows created before this migration keep scope
-- 'read' and no service user: they never wrote anything and cannot start now — a key's scope is
-- immutable, like the key itself (rotate by creating a new client). No data is rewritten.
ALTER TABLE integration_clients
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'read' CHECK (scope IN ('read', 'write')),
    ADD COLUMN service_user_id BIGINT NULL REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_integration_clients_write_needs_service_user
        CHECK (scope = 'read' OR service_user_id IS NOT NULL);
CREATE UNIQUE INDEX uq_integration_clients_service_user
    ON integration_clients(service_user_id) WHERE service_user_id IS NOT NULL;
