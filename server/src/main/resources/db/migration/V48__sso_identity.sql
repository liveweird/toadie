-- SSO identity (OIDC login, behind SSO_ENABLED): an account may be reached through an external
-- identity provider instead of (or beside) a password. password_hash becomes nullable — a NULL hash
-- is a PASSWORDLESS account: every verify path still pays the bcrypt equalizer and fails, so the
-- account can only sign in through its linked identity (or after an admin sets a password).
-- (sso_issuer, sso_subject) is the stable external identity; the email stays Toadie's own and is
-- never overwritten from a claim.
ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN sso_issuer VARCHAR(512) NULL,
    ADD COLUMN sso_subject VARCHAR(255) NULL,
    -- Both halves or neither: a link without an issuer (or a subject) identifies nobody.
    ADD CONSTRAINT ck_users_sso_identity_pair
        CHECK ((sso_issuer IS NULL) = (sso_subject IS NULL)),
    -- A service account never authenticates, so it can never carry an external identity.
    ADD CONSTRAINT ck_users_service_account_no_sso
        CHECK (NOT (service_account AND sso_subject IS NOT NULL));

-- A FULL unique index, deliberately NOT partial over active rows (the exception to the
-- soft-delete idiom): a soft-deleted user keeps its link, so deleting an account really revokes
-- its SSO access and the same external identity cannot silently recreate it. NULL pairs (every
-- password-only account) never collide.
CREATE UNIQUE INDEX uq_users_sso_identity ON users (sso_issuer, sso_subject);
