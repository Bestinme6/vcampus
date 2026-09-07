USE vcampus;

-- Creating this table alone does not mark an old database as current.
-- The server writes the fingerprint only after schema and seed both succeed.
CREATE TABLE IF NOT EXISTS vcampus_schema_state (
    singleton_id TINYINT PRIMARY KEY,
    script_fingerprint CHAR(64) NOT NULL,
    installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_vcampus_schema_singleton CHECK (singleton_id = 1)
);
