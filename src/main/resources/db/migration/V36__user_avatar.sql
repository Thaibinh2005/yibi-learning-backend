-- The Google profile picture, refreshed at each Google sign-in; null shows the initial instead.
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500);

COMMENT ON COLUMN users.avatar_url IS 'Profile picture link (Google sign-in); null means show the initial';
