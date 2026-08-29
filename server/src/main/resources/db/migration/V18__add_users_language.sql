-- Per-user language (Lettuce's V61, ported): drives the UI at sign-in and the language of
-- every server-composed email (password reset, MFA code). ADMIN sets it at create (default
-- English); self or ADMIN may change it via PUT /api/v1/users/{id}/language. No CHECK — the
-- application constant SUPPORTED_LANGUAGES (dictionaries/Languages.kt) is the whitelist
-- (the V12 idiom: the V1 role-CHECK is the deliberate exception, not the rule).
ALTER TABLE users ADD COLUMN language VARCHAR(10) NOT NULL DEFAULT 'en';
