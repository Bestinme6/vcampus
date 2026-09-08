CREATE TABLE IF NOT EXISTS forum_reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reporter_user_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(24) NOT NULL,
    detail VARCHAR(300) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    resolution_action VARCHAR(16) NULL,
    resolution_note VARCHAR(255) NULL,
    handled_by_user_id BIGINT NULL,
    handled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_forum_reporter_target (reporter_user_id, target_type, target_id),
    INDEX idx_forum_report_queue (status, created_at, id),
    INDEX idx_forum_report_target (target_type, target_id, status),
    CONSTRAINT fk_forum_report_reporter FOREIGN KEY (reporter_user_id) REFERENCES users(id),
    CONSTRAINT fk_forum_report_handler FOREIGN KEY (handled_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_report_target CHECK (target_type IN ('POST', 'COMMENT')),
    CONSTRAINT chk_forum_report_reason CHECK (reason IN
        ('SPAM', 'HARASSMENT', 'MISLEADING', 'PRIVACY', 'INAPPROPRIATE', 'OTHER')),
    CONSTRAINT chk_forum_report_status CHECK (status IN ('PENDING', 'DISMISSED', 'ACTIONED')),
    CONSTRAINT chk_forum_report_resolution CHECK
        (resolution_action IS NULL OR resolution_action IN ('DISMISS', 'HIDE', 'DELETE'))
);
