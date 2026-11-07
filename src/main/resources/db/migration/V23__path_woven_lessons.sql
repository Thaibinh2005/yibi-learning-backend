-- V23: Words, characters and grammar are now woven into LESSON nodes instead of being nodes of their own.
-- Paths built before this are removed, and rebuilt at startup in the new shape.
DELETE FROM path_units;

ALTER TABLE path_nodes DROP CONSTRAINT chk_path_node_type;
ALTER TABLE path_nodes ADD CONSTRAINT chk_path_node_type CHECK (node_type IN
    ('LESSON', 'DIALOGUE', 'READING', 'LISTENING', 'CHECKPOINT'));
