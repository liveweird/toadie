-- MFA joins the per-user feature flags (V12) with an INVERTED default: opt-in, so every
-- existing user (the V3 seed admin, soft-deleted rows included) starts with the disabled
-- row present. UserService.create inserts the same row for every user created after this
-- migration.
INSERT INTO user_disabled_features (user_id, feature)
SELECT id, 'MFA' FROM users
ON CONFLICT DO NOTHING;
