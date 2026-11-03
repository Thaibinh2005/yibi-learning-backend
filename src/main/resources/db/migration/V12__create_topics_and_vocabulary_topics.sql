-- V12: Create topics and vocabulary_topics junction table
CREATE TABLE IF NOT EXISTS topics (
    id BIGSERIAL PRIMARY KEY,
    slug VARCHAR(100) UNIQUE NOT NULL,
    title_vi VARCHAR(150) NOT NULL,
    title_zh VARCHAR(150),
    icon VARCHAR(50),
    description TEXT,
    hsk_level VARCHAR(8),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vocabulary_topics (
    topic_id BIGINT REFERENCES topics(id) ON DELETE CASCADE,
    raw_record_id BIGINT REFERENCES hsk_vocabulary_records(raw_record_id) ON DELETE CASCADE,
    PRIMARY KEY (topic_id, raw_record_id)
);

CREATE INDEX IF NOT EXISTS idx_vocab_topics_topic ON vocabulary_topics(topic_id);
CREATE INDEX IF NOT EXISTS idx_vocab_topics_vocab ON vocabulary_topics(raw_record_id);
