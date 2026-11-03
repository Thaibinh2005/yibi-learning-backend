-- V14: Example sentences (V13) are looked up by word (simplified) across all
-- source records of that word, so index the Hanzi column on its own.
CREATE INDEX IF NOT EXISTS idx_hsk_vocab_simplified ON hsk_vocabulary_records(simplified);
