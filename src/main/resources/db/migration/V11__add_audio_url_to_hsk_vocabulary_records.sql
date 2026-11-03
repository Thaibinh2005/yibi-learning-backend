-- ============================================================
-- V11: Add audio_url column to hsk_vocabulary_records
-- ============================================================

ALTER TABLE hsk_vocabulary_records
  ADD COLUMN IF NOT EXISTS audio_url text;

CREATE INDEX IF NOT EXISTS idx_hsk_vocab_audio_url
  ON hsk_vocabulary_records (audio_url)
  WHERE audio_url IS NOT NULL;

