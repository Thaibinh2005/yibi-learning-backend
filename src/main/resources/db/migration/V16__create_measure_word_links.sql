-- V16: Measure word (量词) <-> noun links parsed from the CC-CEDICT "CL:" notes
-- kept in hsk_vocabulary_records.definitions_source (scripts/extract_measure_words.py).
-- Source rows are never modified; this table is derived and can be rebuilt.
-- IF NOT EXISTS because the extraction script may create it before Flyway runs.

CREATE TABLE IF NOT EXISTS measure_word_links (
    id                       BIGSERIAL PRIMARY KEY,
    noun                     VARCHAR(100) NOT NULL,  -- simplified noun, homograph digits stripped (会1 -> 会)
    noun_reading             VARCHAR(100),           -- reading the CL note belongs to, e.g. 'shū'
    measure_word             VARCHAR(20)  NOT NULL,  -- simplified, e.g. 个
    measure_word_traditional VARCHAR(20),            -- traditional form when different, e.g. 個
    measure_word_pinyin      VARCHAR(40)  NOT NULL,  -- tone marks, e.g. 'gè'
    sort_order               SMALLINT     NOT NULL,  -- order inside the CL note (1 = most common)
    noun_record_id           BIGINT NOT NULL REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE CASCADE,
    measure_word_record_id   BIGINT REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_measure_word_link UNIQUE (noun, measure_word)
);

CREATE INDEX IF NOT EXISTS idx_measure_word_links_mw ON measure_word_links(measure_word);
CREATE INDEX IF NOT EXISTS idx_measure_word_links_noun ON measure_word_links(noun);

COMMENT ON TABLE measure_word_links IS 'Which measure words (量词) go with which nouns, parsed from CC-CEDICT CL: notes.';
