-- Hanzi writing: the HSK writing characters split into initial, final and tone (for the syllable composer),
-- and the spaced-repetition cards for characters a learner wrote badly.
CREATE TABLE hanzi_syllables (
    id          BIGSERIAL PRIMARY KEY,
    hanzi       VARCHAR(8)  NOT NULL,
    pinyin      VARCHAR(16) NOT NULL,
    initial     VARCHAR(2)  NOT NULL,
    final_sound VARCHAR(8)  NOT NULL,
    tone        SMALLINT    NOT NULL CHECK (tone BETWEEN 1 AND 5),
    level_code  VARCHAR(8)  NOT NULL,
    CONSTRAINT uq_hanzi_syllable UNIQUE (hanzi, pinyin)
);
CREATE INDEX idx_hanzi_syllables_parts ON hanzi_syllables (initial, final_sound, tone);

CREATE TABLE srs_writing_cards (
    id            BIGSERIAL PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    hanzi         VARCHAR(8)   NOT NULL,
    ease_factor   NUMERIC(4,2) NOT NULL DEFAULT 2.50,
    interval_days INTEGER      NOT NULL DEFAULT 1,
    repetitions   INTEGER      NOT NULL DEFAULT 0,
    due_date      DATE         NOT NULL DEFAULT CURRENT_DATE,
    last_reviewed TIMESTAMP,
    CONSTRAINT uq_srs_writing_user_hanzi UNIQUE (user_id, hanzi)
);
CREATE INDEX idx_srs_writing_due ON srs_writing_cards (user_id, due_date);
