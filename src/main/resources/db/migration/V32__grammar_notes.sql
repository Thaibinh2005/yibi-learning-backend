-- Vietnamese notes on the syllabus grammar points: a title, a structure, an explanation and examples. A point
-- with no row here is shown as its bare syllabus line. status: DRAFT until the operator has read it.
CREATE TABLE grammar_notes (
    raw_record_id  BIGINT PRIMARY KEY REFERENCES hsk_grammar_records (raw_record_id) ON DELETE CASCADE,
    title_vi       VARCHAR(160) NOT NULL,
    structure      VARCHAR(200),
    explanation_vi TEXT         NOT NULL,
    examples       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_grammar_notes_status CHECK (status IN ('DRAFT', 'REVIEWED'))
);
