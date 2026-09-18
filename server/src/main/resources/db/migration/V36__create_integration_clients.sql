-- Dedicated machine identities for the read-only Port GraphQL API. Keys are shown once;
-- only SHA-256 digests persist. Terminal revocation is the removal (not soft deletion);
-- revoked registry rows remain visible to administrators. No ontology data is changed.
CREATE TABLE integration_clients (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL,
    created_by   BIGINT       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at   BIGINT       NOT NULL,
    last_used_at BIGINT       NULL,
    revoked_at   BIGINT       NULL
);
CREATE UNIQUE INDEX uq_integration_clients_key_hash ON integration_clients(key_hash);
