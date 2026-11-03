-- Reusable HSK question bank and per-user skill mastery for adaptive practice.
-- Existing assessment, lesson, account, and HSK source tables remain intact.

CREATE TABLE question_bank_items (
    id                  BIGSERIAL PRIMARY KEY,
    question_code       VARCHAR(120) NOT NULL UNIQUE,
    question_type       VARCHAR(32) NOT NULL,
    skill_key           VARCHAR(32) NOT NULL,
    standard_code       VARCHAR(40) NOT NULL DEFAULT 'NEW_HSK',
    version_code        VARCHAR(40) NOT NULL DEFAULT '2025',
    level_code          VARCHAR(8),
    topic_key           VARCHAR(160) NOT NULL DEFAULT 'general',
    prompt              TEXT NOT NULL,
    prompt_pinyin       TEXT,
    prompt_audio_url    TEXT,
    answer_text         TEXT,
    answer_data         JSONB NOT NULL DEFAULT '{}'::jsonb,
    explanation         TEXT,
    difficulty          SMALLINT NOT NULL DEFAULT 1,
    origin              VARCHAR(24) NOT NULL DEFAULT 'AI_GENERATED',
    status              VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    lesson_id           BIGINT REFERENCES lessons(id) ON DELETE SET NULL,
    content_hash        CHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_question_bank_type CHECK (question_type IN (
        'VOCAB_MEANING', 'MEANING_HANZI', 'CLOZE', 'GRAMMAR_SELECT',
        'SENTENCE_ORDER', 'ERROR_CORRECTION', 'LISTEN_WORD',
        'LISTEN_SENTENCE', 'LISTEN_DIALOGUE', 'READING_COMPREHENSION',
        'TRANSLATION', 'KEYWORD_SENTENCE'
    )),
    CONSTRAINT chk_question_bank_skill CHECK (skill_key IN (
        'vocabulary', 'phonetics', 'characters', 'grammar', 'listening',
        'reading', 'writing', 'translation'
    )),
    CONSTRAINT chk_question_bank_level CHECK (level_code IS NULL OR level_code IN ('1','2','3','4','5','6','7-9')),
    CONSTRAINT chk_question_bank_difficulty CHECK (difficulty BETWEEN 1 AND 5),
    CONSTRAINT chk_question_bank_origin CHECK (origin IN ('SOURCE', 'AI_GENERATED', 'HUMAN')),
    CONSTRAINT chk_question_bank_status CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'RETIRED'))
);

CREATE INDEX idx_question_bank_pool
    ON question_bank_items(status, standard_code, version_code, level_code, skill_key, topic_key, difficulty);
CREATE INDEX idx_question_bank_lesson ON question_bank_items(lesson_id) WHERE lesson_id IS NOT NULL;
CREATE UNIQUE INDEX uq_question_bank_content_hash
    ON question_bank_items(content_hash) WHERE content_hash IS NOT NULL;

CREATE TABLE question_bank_choices (
    id              BIGSERIAL PRIMARY KEY,
    question_id     BIGINT NOT NULL REFERENCES question_bank_items(id) ON DELETE CASCADE,
    choice_key      VARCHAR(12) NOT NULL,
    choice_order    SMALLINT NOT NULL,
    choice_text     TEXT NOT NULL,
    choice_audio_url TEXT,
    is_correct      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (question_id, choice_key),
    UNIQUE (question_id, choice_order),
    UNIQUE (id, question_id),
    CONSTRAINT chk_question_choice_order CHECK (choice_order > 0)
);

CREATE INDEX idx_question_choices_question ON question_bank_choices(question_id, choice_order);

CREATE TABLE question_bank_sources (
    id                  BIGSERIAL PRIMARY KEY,
    question_id         BIGINT NOT NULL REFERENCES question_bank_items(id) ON DELETE CASCADE,
    raw_record_id       BIGINT REFERENCES hsk_raw_records(id) ON DELETE RESTRICT,
    source_section      VARCHAR(40),
    source_item_index   INTEGER,
    evidence_text       TEXT,
    attribution_note    TEXT,
    CONSTRAINT chk_question_source_reference CHECK (
        raw_record_id IS NOT NULL OR (source_section IS NOT NULL AND source_item_index IS NOT NULL)
    ),
    CONSTRAINT chk_question_source_item_index CHECK (source_item_index IS NULL OR source_item_index >= 0)
);

CREATE INDEX idx_question_bank_sources_raw ON question_bank_sources(raw_record_id) WHERE raw_record_id IS NOT NULL;
CREATE UNIQUE INDEX uq_question_bank_source_raw ON question_bank_sources(question_id, raw_record_id)
    WHERE raw_record_id IS NOT NULL;
CREATE UNIQUE INDEX uq_question_bank_source_lesson_item
    ON question_bank_sources(question_id, source_section, source_item_index)
    WHERE raw_record_id IS NULL;

CREATE TABLE practice_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mode            VARCHAR(24) NOT NULL,
    standard_code   VARCHAR(40) NOT NULL DEFAULT 'NEW_HSK',
    version_code    VARCHAR(40) NOT NULL DEFAULT '2025',
    level_code      VARCHAR(8),
    lesson_id       BIGINT REFERENCES lessons(id) ON DELETE SET NULL,
    session_state   VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMPTZ,
    UNIQUE (id, user_id),
    CONSTRAINT chk_practice_session_mode CHECK (mode IN ('LESSON', 'ADAPTIVE', 'REVIEW', 'DIAGNOSTIC')),
    CONSTRAINT chk_practice_session_level CHECK (level_code IS NULL OR level_code IN ('1','2','3','4','5','6','7-9')),
    CONSTRAINT chk_practice_session_state CHECK (session_state IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED'))
);

CREATE INDEX idx_practice_sessions_user_started ON practice_sessions(user_id, started_at DESC);

CREATE TABLE question_attempts (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              BIGINT NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question_id             BIGINT NOT NULL REFERENCES question_bank_items(id) ON DELETE RESTRICT,
    selected_choice_id      BIGINT REFERENCES question_bank_choices(id) ON DELETE SET NULL,
    submitted_answer        JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_correct              BOOLEAN NOT NULL,
    response_time_ms        INTEGER NOT NULL DEFAULT 0,
    attempted_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_question_attempt_session_owner
        FOREIGN KEY (session_id, user_id) REFERENCES practice_sessions(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_question_attempt_choice_question
        FOREIGN KEY (selected_choice_id, question_id) REFERENCES question_bank_choices(id, question_id) ON DELETE SET NULL (selected_choice_id),
    CONSTRAINT chk_question_attempt_time CHECK (response_time_ms >= 0)
);

CREATE INDEX idx_question_attempts_user_time ON question_attempts(user_id, attempted_at DESC);
CREATE INDEX idx_question_attempts_user_question ON question_attempts(user_id, question_id, attempted_at DESC);
CREATE INDEX idx_question_attempts_session ON question_attempts(session_id, attempted_at);

CREATE TABLE learner_skill_mastery (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    standard_code       VARCHAR(40) NOT NULL DEFAULT 'NEW_HSK',
    version_code        VARCHAR(40) NOT NULL DEFAULT '2025',
    level_code          VARCHAR(8) NOT NULL DEFAULT 'UNASSIGNED',
    skill_key           VARCHAR(32) NOT NULL,
    topic_key           VARCHAR(160) NOT NULL DEFAULT 'general',
    attempts_count      INTEGER NOT NULL DEFAULT 0,
    correct_count       INTEGER NOT NULL DEFAULT 0,
    mastery_score       NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    correct_streak      INTEGER NOT NULL DEFAULT 0,
    incorrect_streak    INTEGER NOT NULL DEFAULT 0,
    interval_days       INTEGER NOT NULL DEFAULT 0,
    last_answered_at    TIMESTAMPTZ,
    next_review_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, standard_code, version_code, level_code, skill_key, topic_key),
    CONSTRAINT chk_mastery_level CHECK (level_code IN ('1','2','3','4','5','6','7-9','UNASSIGNED')),
    CONSTRAINT chk_mastery_skill CHECK (skill_key IN (
        'vocabulary', 'phonetics', 'characters', 'grammar', 'listening', 'reading', 'writing', 'translation'
    )),
    CONSTRAINT chk_mastery_counts CHECK (attempts_count >= 0 AND correct_count >= 0 AND correct_count <= attempts_count),
    CONSTRAINT chk_mastery_score CHECK (mastery_score BETWEEN 0 AND 1),
    CONSTRAINT chk_mastery_streaks CHECK (correct_streak >= 0 AND incorrect_streak >= 0),
    CONSTRAINT chk_mastery_interval CHECK (interval_days >= 0)
);

CREATE INDEX idx_learner_mastery_due
    ON learner_skill_mastery(user_id, next_review_at, mastery_score);
CREATE INDEX idx_learner_mastery_topic
    ON learner_skill_mastery(user_id, skill_key, topic_key, mastery_score);

COMMENT ON TABLE question_bank_items IS 'Reusable reviewed or generated HSK exercises with answer key, skill, level, and provenance.';
COMMENT ON TABLE question_bank_choices IS 'Normalized answer choices; exactly one correct choice for multiple-choice questions is enforced by application validation.';
COMMENT ON TABLE question_bank_sources IS 'Evidence and source-row links supporting each question; keeps question provenance reviewable.';
COMMENT ON TABLE practice_sessions IS 'A learner practice run assembled from lesson, adaptive, review, or diagnostic questions.';
COMMENT ON TABLE question_attempts IS 'Append-only answers. Repeated attempts are preserved for mastery estimation.';
COMMENT ON TABLE learner_skill_mastery IS 'Per-user mastery and spaced-review schedule by HSK version, level, skill, and topic.';
