-- Adaptive (CAT/IRT) placement test: onboarding flags, attempt state on the existing placement tables,
-- IRT parameters per question, and one stored result per finished attempt.
-- Existing placement_assessments and skill_attempts rows stay valid and are marked LEGACY.

ALTER TABLE users
    ADD COLUMN onboarding_completed_at TIMESTAMP,
    ADD COLUMN self_reported_level     SMALLINT,
    ADD COLUMN start_mode              VARCHAR(16),
    ADD CONSTRAINT chk_users_self_level CHECK (self_reported_level IS NULL OR self_reported_level BETWEEN 0 AND 9),
    ADD CONSTRAINT chk_users_start_mode CHECK (start_mode IS NULL OR start_mode IN ('BASIC', 'PLACEMENT'));

-- Accounts that exist before this change are not sent through the survey.
UPDATE users SET onboarding_completed_at = NOW() WHERE onboarding_completed_at IS NULL;

ALTER TABLE placement_assessments
    ADD COLUMN status            VARCHAR(16)  NOT NULL DEFAULT 'FINISHED',
    ADD COLUMN mode              VARCHAR(16)  NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN theta             NUMERIC(6,3),
    ADD COLUMN std_error         NUMERIC(6,3),
    ADD COLUMN estimated_level   SMALLINT,
    ADD COLUMN confidence        VARCHAR(8),
    ADD COLUMN algorithm_version VARCHAR(24),
    ADD CONSTRAINT chk_placement_status     CHECK (status IN ('IN_PROGRESS', 'FINISHED', 'ABANDONED')),
    ADD CONSTRAINT chk_placement_mode       CHECK (mode IN ('LEGACY', 'ADAPTIVE')),
    ADD CONSTRAINT chk_placement_estimated  CHECK (estimated_level IS NULL OR estimated_level BETWEEN 1 AND 9),
    ADD CONSTRAINT chk_placement_confidence CHECK (confidence IS NULL OR confidence IN ('LOW', 'MEDIUM', 'HIGH'));

-- New attempts start IN_PROGRESS; only rows written before this migration default to FINISHED.
ALTER TABLE placement_assessments ALTER COLUMN status SET DEFAULT 'IN_PROGRESS';
ALTER TABLE placement_assessments ALTER COLUMN mode SET DEFAULT 'ADAPTIVE';

CREATE INDEX idx_placement_assessments_user_status
    ON placement_assessments(user_id, status, started_at DESC);

ALTER TABLE skill_attempts
    ADD COLUMN question_id         BIGINT REFERENCES question_bank_items(id) ON DELETE SET NULL,
    ADD COLUMN selected_choice_id  BIGINT REFERENCES question_bank_choices(id) ON DELETE SET NULL,
    ADD COLUMN display_order       SMALLINT,
    ADD COLUMN theta_before        NUMERIC(6,3),
    ADD CONSTRAINT chk_skill_attempt_order CHECK (display_order IS NULL OR display_order > 0);

CREATE INDEX idx_skill_attempts_question ON skill_attempts(question_id) WHERE question_id IS NOT NULL;

ALTER TABLE question_bank_items DROP CONSTRAINT chk_question_bank_status;
ALTER TABLE question_bank_items ADD CONSTRAINT chk_question_bank_status CHECK (
    status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'RETIRED', 'REJECTED'));

ALTER TABLE question_bank_items DROP CONSTRAINT chk_question_bank_skill;
ALTER TABLE question_bank_items ADD CONSTRAINT chk_question_bank_skill CHECK (skill_key IN (
    'vocabulary', 'phonetics', 'characters', 'grammar', 'listening',
    'reading', 'writing', 'translation', 'speaking'));

ALTER TABLE question_bank_items DROP CONSTRAINT chk_question_bank_type;
ALTER TABLE question_bank_items ADD CONSTRAINT chk_question_bank_type CHECK (question_type IN (
    'VOCAB_MEANING', 'MEANING_HANZI', 'PHONETICS_SELECT', 'CLOZE', 'GRAMMAR_SELECT',
    'SENTENCE_ORDER', 'ERROR_CORRECTION', 'LISTEN_WORD', 'LISTEN_SENTENCE',
    'LISTEN_DIALOGUE', 'READING_COMPREHENSION', 'TRANSLATION', 'KEYWORD_SENTENCE',
    'READ_ALOUD'));

CREATE TABLE question_calibration (
    question_id  BIGINT PRIMARY KEY REFERENCES question_bank_items(id) ON DELETE CASCADE,
    irt_a        NUMERIC(5,3) NOT NULL DEFAULT 1.000,
    irt_b        NUMERIC(6,3) NOT NULL,
    source       VARCHAR(16)  NOT NULL DEFAULT 'ESTIMATED',
    sample_size  INTEGER      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_calibration_a      CHECK (irt_a > 0),
    CONSTRAINT chk_calibration_source CHECK (source IN ('ESTIMATED', 'CALIBRATED')),
    CONSTRAINT chk_calibration_sample CHECK (sample_size >= 0)
);

-- Starting estimates: b is the HSK level (the 7-9 band counts as 8) shifted by 0.25 per difficulty step from 3.
INSERT INTO question_calibration (question_id, irt_b)
SELECT id,
       (CASE level_code WHEN '7-9' THEN 8 ELSE level_code::numeric END) + (difficulty - 3) * 0.25
FROM question_bank_items
WHERE level_code IS NOT NULL;

CREATE TABLE placement_results (
    assessment_id        BIGINT PRIMARY KEY REFERENCES placement_assessments(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    estimated_level      SMALLINT NOT NULL,
    confidence           VARCHAR(8) NOT NULL,
    theta                NUMERIC(6,3) NOT NULL,
    std_error            NUMERIC(6,3) NOT NULL,
    question_count       INTEGER NOT NULL,
    skill_scores         JSONB NOT NULL DEFAULT '[]'::jsonb,
    strengths            JSONB NOT NULL DEFAULT '[]'::jsonb,
    weaknesses           JSONB NOT NULL DEFAULT '[]'::jsonb,
    weak_topics          JSONB NOT NULL DEFAULT '[]'::jsonb,
    recommended_level    SMALLINT,
    recommended_course_id BIGINT REFERENCES courses(id) ON DELETE SET NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_result_level       CHECK (estimated_level BETWEEN 1 AND 9),
    CONSTRAINT chk_result_confidence  CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT chk_result_recommended CHECK (recommended_level IS NULL OR recommended_level BETWEEN 1 AND 9),
    CONSTRAINT chk_result_count       CHECK (question_count >= 0)
);

CREATE INDEX idx_placement_results_user ON placement_results(user_id, created_at DESC);

COMMENT ON TABLE question_calibration IS 'IRT 2PL parameters per question; ESTIMATED until calibrated from real answers.';
COMMENT ON TABLE placement_results IS 'One row per finished adaptive placement attempt: level, confidence and per-skill breakdown.';
