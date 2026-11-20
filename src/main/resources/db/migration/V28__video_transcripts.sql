-- Chinese subtitles of YouTube videos, saved only after an admin reviewed and edited the preview, as the source of
-- dictation and shadowing exercises. The segments are always read and edited together, so they stay one JSONB array
-- of {text, start, duration, end} in seconds; nothing is stored for a preview the admin did not save.
CREATE TABLE video_transcripts (
    id            BIGSERIAL PRIMARY KEY,
    video_id      VARCHAR(11)  NOT NULL CHECK (video_id ~ '^[A-Za-z0-9_-]{11}$'),
    title         VARCHAR(200) NOT NULL,
    language_code VARCHAR(20)  NOT NULL,
    is_generated  BOOLEAN      NOT NULL,
    hsk_level     VARCHAR(5),
    usage_rights  VARCHAR(20)  NOT NULL CHECK (usage_rights IN ('OWN', 'CREATIVE_COMMONS', 'PERMISSION')),
    rights_note   VARCHAR(500),
    segments      JSONB        NOT NULL,
    segment_count INT          NOT NULL,
    created_by    UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_video_transcript_language UNIQUE (video_id, language_code)
);

COMMENT ON COLUMN video_transcripts.usage_rights IS 'Why the video may be used: own channel, Creative Commons licence, or written permission';
COMMENT ON COLUMN video_transcripts.is_generated IS 'True when the track was YouTube auto-generated captions';
