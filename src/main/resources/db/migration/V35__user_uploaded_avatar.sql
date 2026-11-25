-- A picture the learner uploaded on the account page (a media storage key). It wins over the Google picture
-- in avatar_url, which keeps being refreshed at Google sign-in so removing the upload falls back to it.
ALTER TABLE users ADD COLUMN avatar_key VARCHAR(80);

COMMENT ON COLUMN users.avatar_key IS 'Media storage key of the uploaded picture; null uses avatar_url (Google) or the initial';
