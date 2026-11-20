-- Listening material becomes a shared, community library.
--
-- A YouTube video belongs to its channel, not to whoever pasted the link, so the video itself moves to media_sources
-- (one row per YouTube video, or one per uploaded file). What a learner writes on top of it (lines, pinyin,
-- translation) is a lesson: video_transcripts is renamed listening_lessons ("lessons" is taken by the learning path)
-- and owner_id becomes contributor_id. A lesson is private until its contributor submits it and an admin approves it;
-- only then do other learners see it, in the categories the admin manages.

-- ── The videos and files ─────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE media_sources (
    id           BIGSERIAL    PRIMARY KEY,
    provider     VARCHAR(10)  NOT NULL CHECK (provider IN ('YOUTUBE', 'UPLOAD')),
    external_id  VARCHAR(11)  CHECK (external_id ~ '^[A-Za-z0-9_-]{11}$'),
    title        VARCHAR(300),
    channel_name VARCHAR(200),
    channel_url  VARCHAR(300),
    media_path   VARCHAR(300),
    media_type   VARCHAR(100),
    media_bytes  BIGINT,
    uploaded_by  UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_media_source_kind CHECK (
        (provider = 'YOUTUBE' AND external_id IS NOT NULL AND media_path IS NULL)
        OR (provider = 'UPLOAD' AND media_path IS NOT NULL))
);
CREATE UNIQUE INDEX uq_media_sources_youtube ON media_sources (external_id) WHERE provider = 'YOUTUBE';

COMMENT ON TABLE media_sources IS 'The video or file a lesson plays. A YouTube video is credited to its channel; nobody in the app owns it';
COMMENT ON COLUMN media_sources.uploaded_by IS 'Who uploaded the file (UPLOAD only); not an owner of the content';

INSERT INTO media_sources (provider, external_id, title)
SELECT 'YOUTUBE', video_id, min(title) FROM video_transcripts WHERE video_id IS NOT NULL GROUP BY video_id;

-- ── Lessons ──────────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE video_transcripts RENAME TO listening_lessons;
ALTER TABLE listening_lessons RENAME COLUMN owner_id TO contributor_id;

ALTER TABLE listening_lessons
    ADD COLUMN media_source_id BIGINT REFERENCES media_sources (id) ON DELETE RESTRICT,
    ADD COLUMN visibility      VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN description     VARCHAR(500),
    ADD COLUMN submitted_at    TIMESTAMPTZ,
    ADD COLUMN reviewed_by     UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN reviewed_at     TIMESTAMPTZ,
    ADD COLUMN review_note     VARCHAR(500),
    ADD COLUMN published_at    TIMESTAMPTZ,
    ADD COLUMN practice_count  INT          NOT NULL DEFAULT 0,
    ADD COLUMN save_count      INT          NOT NULL DEFAULT 0,
    -- Kept in step with segments on every write, so lists filter and sort without reading the JSON.
    ADD COLUMN translated_count INT         NOT NULL DEFAULT 0,
    ADD COLUMN est_level        VARCHAR(5),
    ADD COLUMN duration_seconds NUMERIC(9, 3),
    ADD COLUMN stats_at         TIMESTAMPTZ,
    ADD CONSTRAINT chk_lesson_visibility
        CHECK (visibility IN ('PRIVATE', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'HIDDEN'));

UPDATE listening_lessons l SET media_source_id = m.id
FROM media_sources m WHERE m.provider = 'YOUTUBE' AND m.external_id = l.video_id;

-- Each uploaded file becomes its own source (lessons never shared a file before).
DO $$
DECLARE r RECORD; new_id BIGINT;
BEGIN
    FOR r IN SELECT id, media_path, media_type, media_bytes, contributor_id, title
             FROM listening_lessons WHERE media_path IS NOT NULL LOOP
        INSERT INTO media_sources (provider, title, media_path, media_type, media_bytes, uploaded_by)
        VALUES ('UPLOAD', r.title, r.media_path, r.media_type, r.media_bytes, r.contributor_id)
        RETURNING id INTO new_id;
        UPDATE listening_lessons SET media_source_id = new_id WHERE id = r.id;
    END LOOP;
END $$;

-- The old admin library: its admin becomes the contributor. Those rows hold raw subtitles without pinyin or
-- translation, so they wait in review until an admin opens them in the editor once and approves them.
UPDATE listening_lessons SET contributor_id = created_by, visibility = 'PENDING_REVIEW', submitted_at = updated_at,
                             status = 'DRAFT'
WHERE contributor_id IS NULL;

DROP INDEX IF EXISTS uq_video_transcript_library;
DROP INDEX IF EXISTS idx_video_transcripts_owner;
ALTER TABLE listening_lessons
    DROP COLUMN video_id,
    DROP COLUMN media_path,
    DROP COLUMN media_type,
    DROP COLUMN media_bytes,
    DROP COLUMN created_by;

-- A contributor who deletes their account leaves their published lessons to the community.
ALTER TABLE listening_lessons DROP CONSTRAINT video_transcripts_owner_id_fkey;
ALTER TABLE listening_lessons ADD CONSTRAINT fk_listening_lessons_contributor
    FOREIGN KEY (contributor_id) REFERENCES users (id) ON DELETE SET NULL;

-- YouTube lessons may be shared because the video is only embedded; typed text is the contributor's own writing.
ALTER TABLE listening_lessons DROP CONSTRAINT chk_video_usage_rights;
ALTER TABLE listening_lessons ADD CONSTRAINT chk_lesson_usage_rights
    CHECK (usage_rights IN ('OWN', 'CREATIVE_COMMONS', 'PERMISSION', 'PERSONAL_STUDY', 'YOUTUBE_EMBED'));

CREATE INDEX idx_listening_lessons_contributor ON listening_lessons (contributor_id, updated_at DESC);
CREATE INDEX idx_listening_lessons_published ON listening_lessons (published_at DESC) WHERE visibility = 'PUBLISHED';
CREATE INDEX idx_listening_lessons_review ON listening_lessons (submitted_at) WHERE visibility = 'PENDING_REVIEW';
CREATE INDEX idx_listening_lessons_media ON listening_lessons (media_source_id);

COMMENT ON COLUMN listening_lessons.contributor_id IS 'Who wrote the lines (credited as contributor); NOT the owner of the video';
COMMENT ON COLUMN listening_lessons.visibility IS 'PRIVATE → PENDING_REVIEW → PUBLISHED, or REJECTED; HIDDEN when an admin takes a published lesson down';

-- ── Categories, managed by admins ────────────────────────────────────────────────────────────────────────────────
CREATE TABLE video_categories (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    slug        VARCHAR(80)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description VARCHAR(300),
    sort_order  INT          NOT NULL DEFAULT 0,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A lesson sits in up to three categories (checked by the service). Contributors suggest, admins decide on review.
CREATE TABLE lesson_categories (
    lesson_id   BIGINT NOT NULL REFERENCES listening_lessons (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES video_categories (id) ON DELETE CASCADE,
    PRIMARY KEY (lesson_id, category_id)
);
CREATE INDEX idx_lesson_categories_category ON lesson_categories (category_id);

INSERT INTO video_categories (name, slug, description, sort_order) VALUES
    ('Hội thoại hằng ngày', 'hoi-thoai-hang-ngay', 'Chào hỏi, mua sắm, hỏi đường và những câu nói dùng mỗi ngày.', 1),
    ('Phim & phim truyền hình', 'phim', 'Trích đoạn phim, phim bộ với lời thoại tự nhiên.', 2),
    ('Động lực', 'dong-luc', 'Bài nói truyền cảm hứng, chia sẻ kinh nghiệm sống.', 3),
    ('Hài hước', 'hai-huoc', 'Tiểu phẩm, video vui để nghe mà không thấy mệt.', 4),
    ('Tin tức', 'tin-tuc', 'Bản tin và phóng sự, tốc độ và từ vựng chuẩn.', 5),
    ('Âm nhạc', 'am-nhac', 'Bài hát có lời, luyện nghe theo giai điệu.', 6),
    ('Du lịch & văn hoá', 'du-lich-van-hoa', 'Khám phá Trung Quốc: ẩm thực, phong tục, địa danh.', 7);

-- ── A learner's own shelf and practice counts ────────────────────────────────────────────────────────────────────
-- Published lessons a learner saved; together with their own contributions they make "Video của tôi".
CREATE TABLE user_saved_lessons (
    user_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lesson_id BIGINT      NOT NULL REFERENCES listening_lessons (id) ON DELETE CASCADE,
    saved_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, lesson_id)
);

-- One row per learner per lesson per day, so practice_count counts learner-days, not page reloads.
CREATE TABLE lesson_practice_days (
    lesson_id BIGINT NOT NULL REFERENCES listening_lessons (id) ON DELETE CASCADE,
    user_id   UUID   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day       DATE   NOT NULL,
    PRIMARY KEY (lesson_id, user_id, day)
);
