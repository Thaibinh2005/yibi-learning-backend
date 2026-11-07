-- HSKK practice papers. The paper itself is rebuilt from tier, content level and seed.
-- Answers keep their score and what the recognizer heard; the audio itself is not kept.
CREATE TABLE hskk_attempts (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tier          VARCHAR(16) NOT NULL CHECK (tier IN ('PRIMARY', 'INTERMEDIATE', 'ADVANCED')),
    content_level SMALLINT    NOT NULL,
    seed          BIGINT      NOT NULL,
    score_percent SMALLINT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX idx_hskk_attempts_user ON hskk_attempts (user_id, finished_at DESC);

CREATE TABLE hskk_answers (
    attempt_id   BIGINT      NOT NULL REFERENCES hskk_attempts (id) ON DELETE CASCADE,
    question_id  VARCHAR(12) NOT NULL,
    score        SMALLINT    NOT NULL,
    transcript   VARCHAR(4000),
    answered_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (attempt_id, question_id)
);
