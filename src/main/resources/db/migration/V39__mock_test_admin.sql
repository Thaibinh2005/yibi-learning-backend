-- Sample papers move from JSON files only to the database, so admins can upload, check and publish new ones.
-- Papers bundled in data/mocktests/*.json are still loaded at startup (origin BUNDLE, published as they are).

CREATE TABLE mock_tests (
    id                 VARCHAR(64)  PRIMARY KEY,
    level              INT          NOT NULL,
    title              VARCHAR(100) NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    origin             VARCHAR(10)  NOT NULL DEFAULT 'IMPORT' CHECK (origin IN ('BUNDLE', 'IMPORT', 'EDITED')),
    draft              JSONB,
    published_version  INT,
    bundle_hash        VARCHAR(64),
    source_audio_key   VARCHAR(80),
    source_pdf_key     VARCHAR(80),
    peaks_key          VARCHAR(80),
    created_by         UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at       TIMESTAMPTZ
);

-- Every publish is kept: an attempt is scored against the version it was started on.
CREATE TABLE mock_test_versions (
    test_id     VARCHAR(64) NOT NULL REFERENCES mock_tests (id) ON DELETE CASCADE,
    version     INT         NOT NULL,
    paper       JSONB       NOT NULL,
    created_by  UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (test_id, version)
);

-- An upload (PDF + recording) being read and cut by yibi-speech-service.
CREATE TABLE mock_test_imports (
    id          BIGSERIAL    PRIMARY KEY,
    level       INT,
    status      VARCHAR(10)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    step        VARCHAR(20),
    progress    INT          NOT NULL DEFAULT 0,
    message     TEXT,
    pdf_name    VARCHAR(200),
    audio_name  VARCHAR(200),
    pdf_key     VARCHAR(80),
    audio_key   VARCHAR(80),
    test_id     VARCHAR(64),
    problems    JSONB,
    created_by  UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mock_test_imports_created ON mock_test_imports (created_at DESC);

-- Pictures and clips of imported papers, served without login at /api/mock-tests/media/<key>.
-- Only keys listed here are served, so learners' own uploads in the same storage stay private.
CREATE TABLE mock_test_media (
    storage_key   VARCHAR(80)  PRIMARY KEY,
    test_id       VARCHAR(64)  NOT NULL,
    content_type  VARCHAR(60)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mock_test_media_test ON mock_test_media (test_id);

ALTER TABLE mock_test_attempts ADD COLUMN paper_version INT;
UPDATE mock_test_attempts SET paper_version = 1;
ALTER TABLE mock_test_attempts ALTER COLUMN paper_version SET NOT NULL;
ALTER TABLE mock_test_attempts ALTER COLUMN paper_version SET DEFAULT 1;

COMMENT ON COLUMN mock_tests.draft IS 'The paper being checked or edited by an admin; NULL when nothing is pending';
COMMENT ON COLUMN mock_test_attempts.answers IS 'Question number → chosen key ("C"), written character(s) ("的"), or written text';
