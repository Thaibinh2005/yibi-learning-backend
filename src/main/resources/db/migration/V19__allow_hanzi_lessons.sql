-- Hanzi lessons group the syllabus character lists, so lessons and their items may now be of type HANZI.
ALTER TABLE lessons DROP CONSTRAINT chk_lesson_content_type;
ALTER TABLE lessons ADD CONSTRAINT chk_lesson_content_type
    CHECK (content_type IN ('VOCABULARY', 'GRAMMAR', 'HANZI', 'READING', 'LISTENING'));

ALTER TABLE lesson_content_items DROP CONSTRAINT chk_lesson_item_type;
ALTER TABLE lesson_content_items ADD CONSTRAINT chk_lesson_item_type
    CHECK (content_type IN ('VOCABULARY', 'GRAMMAR', 'HANZI'));
