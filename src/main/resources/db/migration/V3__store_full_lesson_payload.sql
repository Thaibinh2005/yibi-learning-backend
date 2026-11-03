-- Keep the complete, versioned ETL lesson document so the web app can serve
-- every extracted section without requiring a new table for each section type.
ALTER TABLE lessons
    ADD COLUMN lesson_payload JSONB;
