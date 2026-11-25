-- A learner's go at an official HSK 3.0 sample paper (the papers themselves are JSON in data/mocktests/).
-- PRACTICE shows each answer as soon as it is chosen; EXAM is timed and scored when handed in.
CREATE TABLE mock_test_attempts (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    test_id           VARCHAR(64)  NOT NULL,
    mode              VARCHAR(10)  NOT NULL CHECK (mode IN ('PRACTICE', 'EXAM')),
    answers           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    current_index     INT          NOT NULL DEFAULT 0,
    elapsed_seconds   INT          NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    correct_count     INT,
    question_count    INT,
    score             INT,
    max_score         INT,
    skill_scores      JSONB
);

-- At most one unfinished attempt per learner and paper: "Tiếp tục" always resumes that one.
CREATE UNIQUE INDEX uq_mock_test_open_attempt ON mock_test_attempts (user_id, test_id) WHERE finished_at IS NULL;
CREATE INDEX idx_mock_test_attempts_user ON mock_test_attempts (user_id, test_id, finished_at DESC);

COMMENT ON COLUMN mock_test_attempts.answers IS 'Question number → chosen option key, e.g. {"1": "C"}';
COMMENT ON COLUMN mock_test_attempts.skill_scores IS 'Per skill: {"listening": {"correct": 18, "total": 20, "score": 90}, ...}';
