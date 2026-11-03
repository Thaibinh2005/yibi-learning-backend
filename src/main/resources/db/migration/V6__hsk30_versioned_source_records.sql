-- Versioned, source-preserving HSK records. Existing HSK 2.0/course tables remain untouched.
-- Each normalized row points to one exact source row; duplicate words are never
-- collapsed by Hanzi, Pinyin, meaning, or level.

CREATE TABLE hsk_source_datasets (
    id                  BIGSERIAL PRIMARY KEY,
    dataset_key         VARCHAR(160) NOT NULL UNIQUE,
    standard_code       VARCHAR(40) NOT NULL,
    version_code        VARCHAR(40) NOT NULL,
    repository_url      TEXT NOT NULL,
    source_commit       VARCHAR(80),
    license_name        VARCHAR(160),
    license_url         TEXT,
    license_notes       TEXT,
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    CONSTRAINT chk_hsk_dataset_verification CHECK
        (verification_status IN ('UNVERIFIED', 'REVIEWED', 'APPROVED'))
);

CREATE TABLE hsk_source_files (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES hsk_source_datasets(id) ON DELETE RESTRICT,
    relative_path   TEXT NOT NULL,
    sha256          CHAR(64) NOT NULL,
    format          VARCHAR(16) NOT NULL,
    byte_size       BIGINT NOT NULL CHECK (byte_size >= 0),
    detected_encoding VARCHAR(40),
    record_count    INTEGER NOT NULL DEFAULT 0 CHECK (record_count >= 0),
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (dataset_id, relative_path, sha256)
);

CREATE INDEX idx_hsk_source_files_dataset_path
    ON hsk_source_files(dataset_id, relative_path);

CREATE TABLE hsk_raw_records (
    id                  BIGSERIAL PRIMARY KEY,
    source_file_id      BIGINT NOT NULL REFERENCES hsk_source_files(id) ON DELETE RESTRICT,
    source_row_number   INTEGER NOT NULL CHECK (source_row_number > 0),
    record_kind         VARCHAR(24) NOT NULL,
    raw_text            TEXT,
    raw_record          JSONB,
    record_sha256       CHAR(64) NOT NULL,
    validation_status   VARCHAR(24) NOT NULL DEFAULT 'UNREVIEWED',
    validation_notes    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_hsk_raw_source_row UNIQUE (source_file_id, source_row_number),
    CONSTRAINT chk_hsk_raw_kind CHECK (record_kind IN ('VOCABULARY', 'HANZI', 'GRAMMAR')),
    CONSTRAINT chk_hsk_raw_validation CHECK
        (validation_status IN ('UNREVIEWED', 'VALID', 'NEEDS_REVIEW', 'REJECTED'))
);

CREATE INDEX idx_hsk_raw_record_hash ON hsk_raw_records(record_sha256);
CREATE INDEX idx_hsk_raw_validation ON hsk_raw_records(validation_status, record_kind);

CREATE TABLE hsk_vocabulary_records (
    raw_record_id       BIGINT PRIMARY KEY REFERENCES hsk_raw_records(id) ON DELETE RESTRICT,
    level_code          VARCHAR(8),
    source_level_label  TEXT,
    simplified          VARCHAR(100) NOT NULL,
    traditional         VARCHAR(100),
    part_of_speech      TEXT,
    definitions_source  TEXT,
    definitions_language VARCHAR(8),
    frequency           BIGINT,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'SOURCE_RAW',
    CONSTRAINT chk_hsk_vocab_level CHECK (level_code IS NULL OR level_code IN ('1','2','3','4','5','6','7-9')),
    CONSTRAINT chk_hsk_vocab_frequency CHECK (frequency IS NULL OR frequency >= 0),
    CONSTRAINT chk_hsk_vocab_verification CHECK
        (verification_status IN ('SOURCE_RAW', 'NEEDS_REVIEW', 'VERIFIED'))
);

CREATE INDEX idx_hsk_vocab_level_hanzi ON hsk_vocabulary_records(level_code, simplified);
CREATE INDEX idx_hsk_vocab_traditional ON hsk_vocabulary_records(traditional);

-- A source row may carry several readings/senses. The imported source Pinyin is
-- recorded as one source reading; complex dictionary definitions are kept raw
-- until their sense boundaries can be verified.
CREATE TABLE hsk_vocabulary_readings (
    id                  BIGSERIAL PRIMARY KEY,
    raw_record_id       BIGINT NOT NULL REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE RESTRICT,
    reading_order       SMALLINT NOT NULL CHECK (reading_order > 0),
    pinyin              VARCHAR(300) NOT NULL,
    zhuyin              VARCHAR(300),
    verification_status VARCHAR(24) NOT NULL DEFAULT 'SOURCE_RAW',
    UNIQUE (raw_record_id, reading_order),
    CONSTRAINT chk_hsk_reading_verification CHECK
        (verification_status IN ('SOURCE_RAW', 'NEEDS_REVIEW', 'VERIFIED'))
);

CREATE INDEX idx_hsk_vocabulary_readings_pinyin ON hsk_vocabulary_readings(pinyin);

CREATE TABLE hsk_vocabulary_senses (
    id                  BIGSERIAL PRIMARY KEY,
    raw_record_id       BIGINT NOT NULL REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE RESTRICT,
    sense_order         SMALLINT NOT NULL CHECK (sense_order > 0),
    meaning             TEXT NOT NULL,
    language_code       VARCHAR(8) NOT NULL,
    part_of_speech      TEXT,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'NEEDS_REVIEW',
    UNIQUE (raw_record_id, sense_order),
    CONSTRAINT chk_hsk_sense_verification CHECK
        (verification_status IN ('NEEDS_REVIEW', 'VERIFIED'))
);

CREATE INDEX idx_hsk_vocabulary_senses_language ON hsk_vocabulary_senses(language_code);

CREATE TABLE hsk_vocabulary_reading_senses (
    reading_id BIGINT NOT NULL REFERENCES hsk_vocabulary_readings(id) ON DELETE RESTRICT,
    sense_id   BIGINT NOT NULL REFERENCES hsk_vocabulary_senses(id) ON DELETE RESTRICT,
    PRIMARY KEY (reading_id, sense_id)
);

-- Explicit, reviewable links support many-to-many matching across sources;
-- no equivalence is inferred from Hanzi alone.
CREATE TABLE hsk_vocabulary_record_relations (
    left_raw_record_id  BIGINT NOT NULL REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE RESTRICT,
    right_raw_record_id BIGINT NOT NULL REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE RESTRICT,
    relation_type       VARCHAR(32) NOT NULL,
    review_status       VARCHAR(24) NOT NULL DEFAULT 'PROPOSED',
    rationale           TEXT,
    reviewed_at         TIMESTAMPTZ,
    PRIMARY KEY (left_raw_record_id, right_raw_record_id, relation_type),
    CONSTRAINT chk_hsk_relation_pair CHECK (left_raw_record_id < right_raw_record_id),
    CONSTRAINT chk_hsk_relation_type CHECK
        (relation_type IN ('SAME_LEXEME', 'RELATED_FORM', 'TRANSLATION_EQUIVALENT')),
    CONSTRAINT chk_hsk_relation_status CHECK (review_status IN ('PROPOSED', 'REVIEWED', 'REJECTED'))
);

CREATE INDEX idx_hsk_vocab_relations_right ON hsk_vocabulary_record_relations(right_raw_record_id);

CREATE TABLE hsk_hanzi_records (
    raw_record_id       BIGINT PRIMARY KEY REFERENCES hsk_raw_records(id) ON DELETE RESTRICT,
    level_code          VARCHAR(8),
    source_level_label  TEXT,
    hanzi               VARCHAR(8) NOT NULL,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'SOURCE_RAW',
    CONSTRAINT chk_hsk_hanzi_level CHECK (level_code IS NULL OR level_code IN ('1','2','3','4','5','6','7-9')),
    CONSTRAINT chk_hsk_hanzi_verification CHECK
        (verification_status IN ('SOURCE_RAW', 'NEEDS_REVIEW', 'VERIFIED'))
);

CREATE INDEX idx_hsk_hanzi_level_character ON hsk_hanzi_records(level_code, hanzi);
CREATE INDEX idx_hsk_hanzi_character ON hsk_hanzi_records(hanzi);

CREATE TABLE hsk_grammar_records (
    raw_record_id       BIGINT PRIMARY KEY REFERENCES hsk_raw_records(id) ON DELETE RESTRICT,
    level_code          VARCHAR(8),
    source_level_label  TEXT,
    category            TEXT,
    category_name       TEXT,
    subcategory         TEXT,
    grammar_content     TEXT,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'SOURCE_RAW',
    CONSTRAINT chk_hsk_grammar_level CHECK (level_code IS NULL OR level_code IN ('1','2','3','4','5','6','7-9')),
    CONSTRAINT chk_hsk_grammar_verification CHECK
        (verification_status IN ('SOURCE_RAW', 'NEEDS_REVIEW', 'VERIFIED'))
);

CREATE INDEX idx_hsk_grammar_level ON hsk_grammar_records(level_code);
CREATE INDEX idx_hsk_grammar_category ON hsk_grammar_records(category_name);

COMMENT ON TABLE hsk_source_datasets IS 'Versioned HSK data sources with explicit provenance and licensing notes.';
COMMENT ON TABLE hsk_source_files IS 'Immutable source-file versions identified by SHA-256; changed inputs create a new row.';
COMMENT ON TABLE hsk_raw_records IS 'One source row per record, retained before normalization and validation.';
COMMENT ON TABLE hsk_vocabulary_records IS 'Source-specific vocabulary rows; no Hanzi-only uniqueness or automatic merge.';
COMMENT ON COLUMN hsk_vocabulary_records.definitions_source IS 'Original source-language definitions; not a Vietnamese translation.';
COMMENT ON TABLE hsk_vocabulary_readings IS 'Source-specific pronunciations, preserving multiple readings per vocabulary record.';
COMMENT ON TABLE hsk_vocabulary_senses IS 'Normalized meanings; keep source definitions raw unless sense boundaries are verifiable.';
COMMENT ON TABLE hsk_vocabulary_reading_senses IS 'Many-to-many relationship between pronunciations and meanings.';
COMMENT ON TABLE hsk_vocabulary_record_relations IS 'Human-reviewed links between source rows; no Hanzi-only merge.';
COMMENT ON TABLE hsk_hanzi_records IS 'Source-specific character-level syllabus rows.';
COMMENT ON TABLE hsk_grammar_records IS 'Normalized fields from the source grammar row; empty fields remain NULL.';
