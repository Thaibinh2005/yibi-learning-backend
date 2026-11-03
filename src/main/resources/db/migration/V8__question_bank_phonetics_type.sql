ALTER TABLE question_bank_items DROP CONSTRAINT chk_question_bank_type;
ALTER TABLE question_bank_items ADD CONSTRAINT chk_question_bank_type CHECK (question_type IN (
    'VOCAB_MEANING', 'MEANING_HANZI', 'PHONETICS_SELECT', 'CLOZE', 'GRAMMAR_SELECT',
    'SENTENCE_ORDER', 'ERROR_CORRECTION', 'LISTEN_WORD', 'LISTEN_SENTENCE',
    'LISTEN_DIALOGUE', 'READING_COMPREHENSION', 'TRANSLATION', 'KEYWORD_SENTENCE'
));

ALTER TABLE question_bank_items ADD CONSTRAINT chk_audio_question_has_audio CHECK (
    question_type NOT IN ('LISTEN_WORD', 'LISTEN_SENTENCE', 'LISTEN_DIALOGUE')
    OR prompt_audio_url IS NOT NULL
);
