-- Move runtime references to source-preserving V6 records, then remove the
-- parallel legacy vocabulary/grammar tables. Never guess mappings by Hanzi.
-- Abort before any DDL if legacy/user-owned content still needs an explicit map.
DO $$
DECLARE
    legacy_vocab_count BIGINT;
    legacy_grammar_count BIGINT;
    legacy_srs_count BIGINT;
    legacy_speech_count BIGINT;
    legacy_items_count BIGINT;
    legacy_lessons_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO legacy_vocab_count FROM vocabulary;
    SELECT COUNT(*) INTO legacy_grammar_count FROM grammar_points;
    SELECT COUNT(*) INTO legacy_srs_count FROM srs_cards;
    SELECT COUNT(*) INTO legacy_speech_count FROM speech_records WHERE vocabulary_id IS NOT NULL;
    SELECT COUNT(*) INTO legacy_items_count FROM lesson_content_items;
    SELECT COUNT(*) INTO legacy_lessons_count FROM lessons WHERE content_ref_id IS NOT NULL;

    IF legacy_vocab_count <> 0 OR legacy_grammar_count <> 0 OR legacy_srs_count <> 0
       OR legacy_speech_count <> 0 OR legacy_items_count <> 0 OR legacy_lessons_count <> 0 THEN
        RAISE EXCEPTION 'V10 stopped safely: legacy vocabulary/grammar or references remain (vocabulary=%, grammar=%, srs_cards=%, speech_refs=%, lesson_items=%, lesson_refs=%). Back up and map these rows to exact V6 source records before retrying.',
            legacy_vocab_count, legacy_grammar_count, legacy_srs_count,
            legacy_speech_count, legacy_items_count, legacy_lessons_count;
    END IF;
END $$;

ALTER TABLE srs_cards DROP CONSTRAINT srs_cards_vocabulary_id_fkey;
ALTER TABLE srs_cards DROP CONSTRAINT uq_srs_user_vocab;
ALTER TABLE srs_cards RENAME COLUMN vocabulary_id TO hsk_vocabulary_record_id;
ALTER TABLE srs_cards ADD CONSTRAINT fk_srs_hsk_vocabulary
    FOREIGN KEY (hsk_vocabulary_record_id)
    REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE RESTRICT;
ALTER TABLE srs_cards ADD CONSTRAINT uq_srs_user_hsk_vocabulary
    UNIQUE (user_id, hsk_vocabulary_record_id);

ALTER TABLE speech_records DROP CONSTRAINT speech_records_vocabulary_id_fkey;
ALTER TABLE speech_records RENAME COLUMN vocabulary_id TO hsk_vocabulary_record_id;
ALTER TABLE speech_records ADD CONSTRAINT fk_speech_hsk_vocabulary
    FOREIGN KEY (hsk_vocabulary_record_id)
    REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE SET NULL;

ALTER TABLE lesson_content_items RENAME COLUMN content_id TO raw_record_id;
ALTER TABLE lesson_content_items ADD CONSTRAINT fk_lesson_items_hsk_raw_record
    FOREIGN KEY (raw_record_id) REFERENCES hsk_raw_records(id) ON DELETE RESTRICT;
ALTER TABLE lessons ADD CONSTRAINT fk_lessons_hsk_raw_record
    FOREIGN KEY (content_ref_id) REFERENCES hsk_raw_records(id) ON DELETE SET NULL;

DROP TABLE grammar_points;
DROP TABLE vocabulary;

COMMENT ON COLUMN srs_cards.hsk_vocabulary_record_id IS
    'Exact source-specific V6 vocabulary record; intentionally not deduplicated by Hanzi.';
COMMENT ON COLUMN speech_records.hsk_vocabulary_record_id IS
    'Optional exact source-specific V6 vocabulary record practiced by the learner.';
COMMENT ON COLUMN lesson_content_items.raw_record_id IS
    'V6 raw source record ID; content_type distinguishes vocabulary and grammar.';
COMMENT ON COLUMN lessons.content_ref_id IS
    'Optional V6 raw source record ID selected for this lesson.';
