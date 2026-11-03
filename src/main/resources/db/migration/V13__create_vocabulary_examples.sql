-- V13: Create table for vocabulary example sentences
-- These are generated/curated sentences illustrating how each word is used in context.

CREATE TABLE IF NOT EXISTS hsk_vocabulary_examples (
    id              BIGSERIAL PRIMARY KEY,
    raw_record_id   BIGINT NOT NULL,
    sentence_zh     TEXT NOT NULL,            -- Chinese sentence
    sentence_pinyin TEXT,                     -- Pinyin for the sentence
    translation_vi  TEXT,                     -- Vietnamese translation
    source          VARCHAR(60) DEFAULT 'ai_generated',  -- ai_generated, manual, textbook
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_vocab_example_record
        FOREIGN KEY (raw_record_id) REFERENCES hsk_raw_records(id) ON DELETE CASCADE
);

CREATE INDEX idx_vocab_examples_record ON hsk_vocabulary_examples(raw_record_id);

COMMENT ON TABLE hsk_vocabulary_examples IS 'Example sentences for HSK vocabulary words, keyed by raw_record_id.';
COMMENT ON COLUMN hsk_vocabulary_examples.source IS 'Origin of this example: ai_generated, manual, or textbook.';

