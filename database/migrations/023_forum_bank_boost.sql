ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'SCHEDULE_CHANGED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'LIBRARY_RESERVATION_AVAILABLE',
         'LIBRARY_FEE_CREATED', 'LIBRARY_FEE_PAID', 'LIBRARY_FEE_WAIVED', 'LIBRARY_FEE_REFUNDED',
         'FORUM_POST_COMMENTED', 'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED',
         'FORUM_COMMENT_REPLIED', 'FORUM_ANNOUNCEMENT', 'FORUM_CONTENT_REPORTED',
         'FORUM_REPORT_RESOLVED', 'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP',
         'BANK_ACCOUNT_STATUS_CHANGED', 'BANK_FORUM_BOOST_PAID',
         'SHOP_ORDER_PAID', 'SHOP_ORDER_REFUNDED', 'SHOP_ORDER_SHIPPED'));

ALTER TABLE bank_ledger_entries
    DROP CHECK chk_bank_ledger_type,
    ADD CONSTRAINT chk_bank_ledger_type CHECK (entry_type IN
        ('ADMIN_TOPUP', 'TRANSFER_OUT', 'TRANSFER_IN', 'SHOP_PAYMENT', 'SHOP_REFUND',
         'LIBRARY_FEE', 'LIBRARY_REFUND', 'FORUM_BOOST'));

CREATE TABLE IF NOT EXISTS forum_post_boosts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    sponsor_user_id BIGINT NOT NULL,
    tier VARCHAR(16) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    initial_heat INT NOT NULL,
    operation_id CHAR(36) NOT NULL,
    bank_ledger_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_forum_post_boost_operation (sponsor_user_id, operation_id),
    UNIQUE KEY uk_forum_post_boost_ledger (bank_ledger_id),
    INDEX idx_forum_post_boost_active (post_id, expires_at, created_at),
    INDEX idx_forum_post_boost_sponsor_day
        (sponsor_user_id, post_id, created_at),
    CONSTRAINT fk_forum_post_boost_post FOREIGN KEY (post_id) REFERENCES forum_posts(id),
    CONSTRAINT fk_forum_post_boost_sponsor FOREIGN KEY (sponsor_user_id) REFERENCES users(id),
    CONSTRAINT fk_forum_post_boost_ledger FOREIGN KEY (bank_ledger_id)
        REFERENCES bank_ledger_entries(id),
    CONSTRAINT chk_forum_post_boost_tier CHECK (
        (tier = 'FIVE' AND amount = 5.00 AND initial_heat = 50)
        OR (tier = 'TEN' AND amount = 10.00 AND initial_heat = 100)
        OR (tier = 'FIFTEEN' AND amount = 15.00 AND initial_heat = 150))
);
