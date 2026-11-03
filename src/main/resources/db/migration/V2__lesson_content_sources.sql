-- Preserve the origin of imported course material and allow one lesson to
-- reference many vocabulary and grammar records.
CREATE TABLE course_sources (
    source_id       VARCHAR(120) PRIMARY KEY,
    course_id       BIGINT NOT NULL UNIQUE REFERENCES courses(id) ON DELETE CASCADE,
    book_title      VARCHAR(300) NOT NULL,
    publisher       VARCHAR(300),
    edition         VARCHAR(120),
    hsk_level       INTEGER NOT NULL,
    pdf_name        VARCHAR(500) NOT NULL,
    license_note    TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_course_source_hsk CHECK (hsk_level BETWEEN 1 AND 9)
);

CREATE TABLE lesson_sources (
    id              BIGSERIAL PRIMARY KEY,
    source_id       VARCHAR(120) NOT NULL REFERENCES course_sources(source_id) ON DELETE CASCADE,
    lesson_number   INTEGER NOT NULL,
    lesson_id       BIGINT NOT NULL UNIQUE REFERENCES lessons(id) ON DELETE CASCADE,
    page_start      INTEGER NOT NULL,
    page_end        INTEGER NOT NULL,
    CONSTRAINT uq_lesson_source_number UNIQUE (source_id, lesson_number),
    CONSTRAINT chk_lesson_source_pages CHECK (page_start > 0 AND page_end >= page_start)
);

CREATE TABLE lesson_content_items (
    id              BIGSERIAL PRIMARY KEY,
    lesson_id       BIGINT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    content_type    VARCHAR(20) NOT NULL,
    content_id      BIGINT NOT NULL,
    item_order      INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_lesson_item_type CHECK (content_type IN ('VOCABULARY', 'GRAMMAR')),
    CONSTRAINT uq_lesson_content_item UNIQUE (lesson_id, content_type, content_id)
);

CREATE INDEX idx_lesson_content_items_lesson ON lesson_content_items(lesson_id, item_order);
