-- V22: Questions a learner got wrong on the path. They come back in later quizzes and in a review of their own
-- until answered correctly twice in a row. A snapshot of the question is kept, so no quiz has to be regenerated.
CREATE TABLE path_mistakes (
    id             BIGSERIAL PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    node_id        BIGINT      REFERENCES path_nodes (id) ON DELETE SET NULL,
    hsk_level      SMALLINT    NOT NULL,
    skill_key      VARCHAR(32) NOT NULL,
    question_key   VARCHAR(36) NOT NULL,
    question_type  VARCHAR(32) NOT NULL,
    prompt         TEXT        NOT NULL,
    pinyin         TEXT,
    audio_text     TEXT,
    options        JSONB       NOT NULL,
    answer         TEXT        NOT NULL,
    explanation    TEXT,
    last_picked    TEXT,
    wrong_count    INTEGER     NOT NULL DEFAULT 1,
    correct_streak SMALLINT    NOT NULL DEFAULT 0,
    last_wrong_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT uq_path_mistake_question UNIQUE (user_id, question_key)
);
CREATE INDEX idx_path_mistakes_open ON path_mistakes (user_id, last_seen_at) WHERE resolved_at IS NULL;

COMMENT ON COLUMN path_mistakes.question_key IS 'Identifies the same question across quizzes; derived from its prompt and answer.';
