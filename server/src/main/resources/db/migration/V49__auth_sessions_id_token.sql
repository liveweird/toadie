-- Provider sign-out (OIDC RP-initiated logout): an SSO session keeps the raw ID token the identity
-- provider issued at sign-in, so logout can present it as `id_token_hint` to the provider's
-- end-session endpoint. NULL for a password session, for an SSO session whose token exceeded the
-- retention cap, and for every row that existed before this migration (those sign out locally only).
-- The column lives and dies with the session row: logout and the expiry cleanup delete the row, and
-- with it the token. It is never read by authentication, never logged, never returned except to the
-- session's own logout.
ALTER TABLE auth_sessions ADD COLUMN id_token TEXT NULL;
