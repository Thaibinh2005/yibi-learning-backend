-- Splits the shared HSK 1-2 writing list: a character is level 1 when the level 1 recognition list has it, else level 2.
UPDATE hanzi_syllables s
SET level_code = CASE
    WHEN EXISTS (SELECT 1 FROM hsk_hanzi_records r WHERE r.hanzi = s.hanzi AND r.level_code = '1') THEN '1'
    ELSE '2'
END
WHERE s.level_code = '1-2';
