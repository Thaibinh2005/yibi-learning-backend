-- ============================================================
--  Yibi Platform — Flyway Migration V5
--  Script: V5__lesson_practice_attempts.sql
--  Description: Track per-user answers on AI-generated lesson
--               practice activities (independent of placement
--               assessments).
-- ============================================================

CREATE TABLE lesson_practice_attempts (
    id                  BIGSERIAL       PRIMARY KEY,
    user_id             UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lesson_id           BIGINT          NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    -- Stable ID assigned by AiLessonContentService (e.g. "hsk1-lesson-3-ai-1")
    activity_id         VARCHAR(80)     NOT NULL,
    skill_key           VARCHAR(40)     NOT NULL,
    is_correct          BOOLEAN         NOT NULL,
    selected_answer     TEXT,
    correct_answer      TEXT,
    response_time_ms    INTEGER         NOT NULL DEFAULT 0,
    answered_at         TIMESTAMP       NOT NULL DEFAULT NOW(),

    -- One attempt per user per activity (upsert-friendly)
    CONSTRAINT uq_practice_attempt UNIQUE (user_id, lesson_id, activity_id),
    CONSTRAINT chk_practice_time CHECK (response_time_ms >= 0)
);

CREATE INDEX idx_lpa_user_lesson  ON lesson_practice_attempts(user_id, lesson_id);
CREATE INDEX idx_lpa_user_skill   ON lesson_practice_attempts(user_id, skill_key, answered_at DESC);

COMMENT ON TABLE  lesson_practice_attempts               IS 'Records each user answer on an AI-generated lesson practice activity';
COMMENT ON COLUMN lesson_practice_attempts.activity_id   IS 'Stable AI activity ID from lesson_payload.generated_lesson.activities[].id';
COMMENT ON COLUMN lesson_practice_attempts.skill_key     IS 'vocabulary | phonetics | characters | grammar';
