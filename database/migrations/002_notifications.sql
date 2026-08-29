USE vcampus;

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_user_id BIGINT NOT NULL,
    sender_user_id BIGINT NULL,
    notification_type VARCHAR(40) NOT NULL,
    source_module VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    target VARCHAR(40) NOT NULL DEFAULT 'NONE',
    related_entity_id BIGINT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_recipient_unread_created
        (recipient_user_id, is_read, created_at),
    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users(id),
    CONSTRAINT fk_notification_sender
        FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET')),
    CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY')),
    CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'NONE'))
);
