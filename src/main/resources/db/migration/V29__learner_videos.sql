-- Learners add their own listening material (a YouTube link, an audio/video file, or typed text) for dictation and
-- shadowing. It reuses video_transcripts: owner_id NULL is the admin library, otherwise the row is private to its owner.
ALTER TABLE video_transcripts
    ADD COLUMN owner_id           UUID REFERENCES users (id) ON DELETE CASCADE,
    ADD COLUMN source_type        VARCHAR(10)  NOT NULL DEFAULT 'YOUTUBE',
    ADD COLUMN media_path         VARCHAR(300),
    ADD COLUMN media_type         VARCHAR(100),
    ADD COLUMN media_bytes        BIGINT,
    ADD COLUMN status             VARCHAR(12)  NOT NULL DEFAULT 'READY',
    ADD COLUMN status_message     VARCHAR(300),
    ADD COLUMN translation_status VARCHAR(12)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN translation_message VARCHAR(300),
    ADD CONSTRAINT chk_video_source CHECK (source_type IN ('YOUTUBE', 'UPLOAD', 'MANUAL')),
    ADD CONSTRAINT chk_video_status CHECK (status IN ('DRAFT', 'PROCESSING', 'READY', 'FAILED')),
    ADD CONSTRAINT chk_video_translation CHECK (translation_status IN ('NONE', 'RUNNING', 'DONE', 'FAILED'));

-- A file or typed text has no YouTube id; a learner's own material needs no licence choice.
ALTER TABLE video_transcripts ALTER COLUMN video_id DROP NOT NULL;
ALTER TABLE video_transcripts DROP CONSTRAINT video_transcripts_usage_rights_check;
ALTER TABLE video_transcripts ADD CONSTRAINT chk_video_usage_rights
    CHECK (usage_rights IN ('OWN', 'CREATIVE_COMMONS', 'PERMISSION', 'PERSONAL_STUDY'));

-- Only the admin library keeps one copy per video and language; a learner may add the same video twice.
ALTER TABLE video_transcripts DROP CONSTRAINT uq_video_transcript_language;
CREATE UNIQUE INDEX uq_video_transcript_library ON video_transcripts (video_id, language_code) WHERE owner_id IS NULL;
CREATE INDEX idx_video_transcripts_owner ON video_transcripts (owner_id, updated_at DESC) WHERE owner_id IS NOT NULL;

COMMENT ON COLUMN video_transcripts.owner_id IS 'NULL for the admin library; otherwise the learner who added it, visible only to them';
COMMENT ON COLUMN video_transcripts.media_path IS 'Key of an uploaded file in the media storage (local disk now, S3/R2 later)';

-- Vietnamese translations by Gemini, shared by every learner who studies the same sentence, so a sentence is paid once.
CREATE TABLE segment_translations (
    text_hash   CHAR(64)    PRIMARY KEY,
    source_text TEXT        NOT NULL,
    vi          TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-learner daily counters for the costly steps (adding material, sentences sent to Gemini).
CREATE TABLE learner_daily_usage (
    user_id             UUID    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day                 DATE    NOT NULL,
    videos_added        INT     NOT NULL DEFAULT 0,
    segments_translated INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
);
