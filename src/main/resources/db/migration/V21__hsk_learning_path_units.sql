-- V21: The learning path. Each HSK level is a sequence of themed units made of short nodes, built from the
-- curriculum data. Additive: lessons and courses stay as they are and remain the reference library.
CREATE TABLE path_units (
    id         BIGSERIAL PRIMARY KEY,
    hsk_level  SMALLINT     NOT NULL,
    unit_order INTEGER      NOT NULL,
    title_vi   VARCHAR(200) NOT NULL,
    title_zh   VARCHAR(200),
    icon       VARCHAR(32),
    CONSTRAINT uq_path_unit_order UNIQUE (hsk_level, unit_order),
    CONSTRAINT chk_path_unit_level CHECK (hsk_level BETWEEN 1 AND 7)
);

CREATE TABLE path_nodes (
    id               BIGSERIAL PRIMARY KEY,
    unit_id          BIGINT       NOT NULL REFERENCES path_units (id) ON DELETE CASCADE,
    node_order       INTEGER      NOT NULL,
    node_type        VARCHAR(20)  NOT NULL,
    title            VARCHAR(200) NOT NULL,
    content          JSONB        NOT NULL,
    source_lesson_id BIGINT       REFERENCES lessons (id) ON DELETE SET NULL,
    CONSTRAINT uq_path_node_order UNIQUE (unit_id, node_order),
    CONSTRAINT chk_path_node_type CHECK (node_type IN
        ('VOCABULARY', 'HANZI', 'GRAMMAR', 'DIALOGUE', 'READING', 'LISTENING', 'CHECKPOINT'))
);

CREATE TABLE user_path_progress (
    user_id      UUID     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    node_id      BIGINT   NOT NULL REFERENCES path_nodes (id) ON DELETE CASCADE,
    best_score   SMALLINT NOT NULL DEFAULT 0,
    attempts     INTEGER  NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, node_id)
);

-- One row per answered quiz question: the skill chart and the study statistics read from it.
CREATE TABLE path_answers (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    node_id     BIGINT      REFERENCES path_nodes (id) ON DELETE SET NULL,
    skill_key   VARCHAR(32) NOT NULL,
    is_correct  BOOLEAN     NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_path_answers_user ON path_answers (user_id, answered_at);

-- Seconds of active use per day, reported by the browser while the learner is on the site.
CREATE TABLE study_activity (
    user_id    UUID    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    study_date DATE    NOT NULL,
    seconds    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, study_date)
);

CREATE TABLE mock_exam_results (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    hsk_level     SMALLINT    NOT NULL,
    score_percent SMALLINT    NOT NULL,
    finished_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mock_exam_user ON mock_exam_results (user_id, score_percent DESC);

COMMENT ON TABLE path_units IS 'Themed units of one HSK level; level 7 is the combined HSK 7-9 band.';
COMMENT ON COLUMN path_nodes.content IS 'Everything the node teaches and asks, so a quiz needs no AI call while learning.';
