CREATE TABLE IF NOT EXISTS class_period_times (
    period_number TINYINT PRIMARY KEY,
    start_time TIME NULL,
    end_time TIME NULL,
    updated_by_user_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_period_time_updater
        FOREIGN KEY (updated_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_period_number CHECK (period_number BETWEEN 1 AND 12),
    CONSTRAINT chk_period_time_pair CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR (start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time)
    )
);

INSERT INTO class_period_times (period_number) VALUES
    (1), (2), (3), (4), (5), (6),
    (7), (8), (9), (10), (11), (12)
ON DUPLICATE KEY UPDATE period_number = VALUES(period_number);
