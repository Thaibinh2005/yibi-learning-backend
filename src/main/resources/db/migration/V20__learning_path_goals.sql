-- V20: A learner's goal and study time, collected by the onboarding survey and editable in the learning path.
-- Additive: existing rows keep working with the defaults below.
ALTER TABLE learning_paths
    ADD COLUMN goal_type     VARCHAR(20) NOT NULL DEFAULT 'HSK',
    ADD COLUMN target_score  INTEGER,
    ADD COLUMN target_date   DATE,
    ADD COLUMN daily_minutes INTEGER     NOT NULL DEFAULT 20,
    ADD CONSTRAINT chk_path_goal_type CHECK (goal_type IN ('HSK', 'HSKK', 'COMMUNICATION')),
    ADD CONSTRAINT chk_path_target_score CHECK (target_score IS NULL OR target_score BETWEEN 0 AND 300),
    ADD CONSTRAINT chk_path_daily_minutes CHECK (daily_minutes BETWEEN 5 AND 240);

COMMENT ON COLUMN learning_paths.goal_type IS 'What the learner studies for: HSK, HSKK or COMMUNICATION.';
COMMENT ON COLUMN learning_paths.daily_minutes IS 'Minutes the learner plans to study each day; sizes the daily plan.';
