-- Service accounts (2.15.0): the actor rows behind integration clients. A service account
-- is a users row that can never authenticate — no login, reset link, session, role change,
-- or admin password set — and exists only so entity writes made through the integration
-- MCP endpoint carry an ordinary created_by attribution. Never hard-deleted (the V1 convention).
ALTER TABLE users
    ADD COLUMN service_account BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_users_service_account_never_admin
        CHECK (NOT (service_account AND "role" = 'ADMIN'));
CREATE INDEX idx_users_service_account ON users(service_account);
