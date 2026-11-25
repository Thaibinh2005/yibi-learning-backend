-- Lessons an admin adds for the community are the team's, not the admin's own: they are credited to "Đội ngũ Yibi",
-- managed from the admin library tab, and kept out of the admin account's "Video của tôi".
ALTER TABLE listening_lessons ADD COLUMN editorial BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE listening_lessons l SET editorial = TRUE
FROM users u WHERE u.id = l.contributor_id AND u.role = 'ROLE_ADMIN';

CREATE INDEX idx_listening_lessons_editorial ON listening_lessons (updated_at DESC) WHERE editorial;

COMMENT ON COLUMN listening_lessons.editorial IS 'Added by an admin for the community; shown as "Đội ngũ Yibi" instead of the admin''s username';
