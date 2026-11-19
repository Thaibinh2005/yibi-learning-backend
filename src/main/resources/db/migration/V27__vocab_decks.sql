-- Personal vocabulary decks. A deck is a label over words of the HSK bank: the memory of a word stays in one
-- srs_cards row per user and word, so a word in several decks is learnt once and shows the same progress in each.
CREATE TABLE vocab_decks (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(80)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_vocab_deck_user_name ON vocab_decks (user_id, lower(name));

CREATE TABLE vocab_deck_words (
    deck_id                  BIGINT      NOT NULL REFERENCES vocab_decks (id) ON DELETE CASCADE,
    hsk_vocabulary_record_id BIGINT      NOT NULL REFERENCES hsk_vocabulary_records (raw_record_id) ON DELETE RESTRICT,
    added_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (deck_id, hsk_vocabulary_record_id)
);
CREATE INDEX idx_vocab_deck_words_record ON vocab_deck_words (hsk_vocabulary_record_id);
