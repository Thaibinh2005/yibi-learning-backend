CREATE TABLE placement_assessments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hsk_level           INTEGER NOT NULL DEFAULT 1,
    question_snapshot   JSONB NOT NULL,
    score_percent       NUMERIC(5,2),
    correct_count       INTEGER,
    total_questions     INTEGER NOT NULL,
    started_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    finished_at         TIMESTAMP
);

CREATE INDEX idx_placement_assessments_user_started
    ON placement_assessments(user_id, started_at DESC);

CREATE TABLE skill_attempts (
    id                      BIGSERIAL PRIMARY KEY,
    assessment_id           BIGINT NOT NULL REFERENCES placement_assessments(id) ON DELETE CASCADE,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question_key            VARCHAR(36) NOT NULL,
    skill_key               VARCHAR(40) NOT NULL,
    topic_key               VARCHAR(300),
    lesson_id               BIGINT REFERENCES lessons(id) ON DELETE SET NULL,
    is_correct              BOOLEAN NOT NULL,
    response_time_seconds   INTEGER NOT NULL DEFAULT 0,
    answered_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_skill_attempt_question UNIQUE (assessment_id, question_key),
    CONSTRAINT chk_skill_attempt_time CHECK (response_time_seconds >= 0)
);

CREATE INDEX idx_skill_attempts_user_skill_date
    ON skill_attempts(user_id, skill_key, answered_at DESC);
