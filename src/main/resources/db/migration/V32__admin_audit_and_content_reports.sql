-- What admins did (review, users, categories, reports), so a disputed or mistaken action can be traced.
CREATE TABLE admin_audit_log (
    id             BIGSERIAL    PRIMARY KEY,
    admin_id       UUID         REFERENCES users (id) ON DELETE SET NULL,
    admin_username VARCHAR(50)  NOT NULL,
    action         VARCHAR(40)  NOT NULL,
    target_type    VARCHAR(30)  NOT NULL,
    target_id      VARCHAR(64),
    detail         VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_created ON admin_audit_log (created_at DESC);
CREATE INDEX idx_admin_audit_action ON admin_audit_log (action, created_at DESC);

COMMENT ON COLUMN admin_audit_log.admin_username IS 'Kept even if the admin account is deleted later';

-- Learners report a mistake in content (a listening line, a whole lesson, a word); admins fix it and close the report.
CREATE TABLE content_reports (
    id              BIGSERIAL    PRIMARY KEY,
    reporter_id     UUID         REFERENCES users (id) ON DELETE SET NULL,
    target_type     VARCHAR(30)  NOT NULL CHECK (target_type IN ('LISTENING_SEGMENT', 'LISTENING_LESSON', 'VOCABULARY')),
    target_id       BIGINT       NOT NULL,
    target_ref      INT,
    reason          VARCHAR(30)  NOT NULL CHECK (reason IN ('WRONG_TEXT', 'WRONG_TIME', 'WRONG_TRANSLATION', 'WRONG_PINYIN',
                                                            'WRONG_MEANING', 'BAD_EXAMPLE', 'INAPPROPRIATE', 'OTHER')),
    note            VARCHAR(500),
    status          VARCHAR(12)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    resolved_by     UUID         REFERENCES users (id) ON DELETE SET NULL,
    resolved_at     TIMESTAMPTZ,
    resolution_note VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_content_reports_open ON content_reports (created_at) WHERE status = 'OPEN';
CREATE INDEX idx_content_reports_target ON content_reports (target_type, target_id);
-- One open report per learner per spot: pressing the button twice does not queue two.
CREATE UNIQUE INDEX uq_content_reports_open ON content_reports (reporter_id, target_type, target_id, COALESCE(target_ref, -1), reason)
    WHERE status = 'OPEN';

COMMENT ON COLUMN content_reports.target_ref IS 'Sentence index for LISTENING_SEGMENT; empty otherwise';
