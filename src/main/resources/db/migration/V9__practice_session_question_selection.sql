CREATE TABLE practice_session_questions (
    session_id          BIGINT NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
    question_id         BIGINT NOT NULL REFERENCES question_bank_items(id) ON DELETE RESTRICT,
    display_order       INTEGER NOT NULL,
    selection_reason    VARCHAR(24) NOT NULL,
    selected_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, question_id),
    UNIQUE (session_id, display_order),
    CONSTRAINT chk_session_question_order CHECK (display_order > 0),
    CONSTRAINT chk_session_question_reason CHECK (selection_reason IN ('DUE_REVIEW', 'WEAK_SKILL', 'NEW_CONTENT', 'LESSON_CONTENT'))
);

CREATE INDEX idx_session_questions_order ON practice_session_questions(session_id, display_order);
CREATE INDEX idx_session_questions_question ON practice_session_questions(question_id, session_id);

COMMENT ON TABLE practice_session_questions IS 'Snapshot of which reusable questions were selected for each practice run and why.';
