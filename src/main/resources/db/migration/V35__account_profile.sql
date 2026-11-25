-- The account page: a name shown in the app (the username stays the sign-in name and never changes),
-- and whether the learner knows their password. Google sign-in stores a random one the learner never saw,
-- so those accounts may set a password without typing an old one.
ALTER TABLE users ADD COLUMN display_name VARCHAR(50);
ALTER TABLE users ADD COLUMN password_set BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE users SET password_set = FALSE WHERE google_sub IS NOT NULL;

COMMENT ON COLUMN users.display_name IS 'Shown instead of the username when set; null falls back to the username';
COMMENT ON COLUMN users.password_set IS 'False while the password is a random one from Google sign-in';
