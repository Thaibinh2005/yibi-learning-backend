-- The placement test records speaking results in learner_skill_mastery, which V7 did not allow.

ALTER TABLE learner_skill_mastery DROP CONSTRAINT chk_mastery_skill;
ALTER TABLE learner_skill_mastery ADD CONSTRAINT chk_mastery_skill CHECK (skill_key IN (
    'vocabulary', 'phonetics', 'characters', 'grammar', 'listening',
    'reading', 'writing', 'translation', 'speaking'));
