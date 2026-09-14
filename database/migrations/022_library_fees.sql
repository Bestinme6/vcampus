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
         'BANK_ACCOUNT_STATUS_CHANGED', 'SHOP_ORDER_PAID', 'SHOP_ORDER_REFUNDED', 'SHOP_ORDER_SHIPPED')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'ACADEMIC_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE',
         'LIBRARY_LOANS', 'LIBRARY_CATALOG', 'LIBRARY_FEES', 'FORUM_POST',
         'BANK_LEDGER', 'SHOP_ORDERS', 'NONE'));

ALTER TABLE bank_ledger_entries
    DROP CHECK chk_bank_ledger_type,
    ADD CONSTRAINT chk_bank_ledger_type CHECK (entry_type IN
        ('ADMIN_TOPUP', 'TRANSFER_OUT', 'TRANSFER_IN', 'SHOP_PAYMENT', 'SHOP_REFUND',
         'LIBRARY_FEE', 'LIBRARY_REFUND'));

ALTER TABLE books
    ADD COLUMN replacement_price DECIMAL(10,2) NULL AFTER description,
    ADD CONSTRAINT chk_book_replacement_price
        CHECK (replacement_price IS NULL OR replacement_price >= 0.01);

INSERT INTO library_code_sequences (code_type, next_value)
VALUES ('LIBRARY_FEE_BILL', 1)
ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, VALUES(next_value));

CREATE TABLE IF NOT EXISTS library_fee_policy (
    id TINYINT PRIMARY KEY,
    grace_days INT NOT NULL,
    daily_overdue_fee DECIMAL(10,2) NOT NULL,
    overdue_cap DECIMAL(10,2) NOT NULL,
    minor_damage_rate DECIMAL(5,4) NOT NULL,
    major_damage_rate DECIMAL(5,4) NOT NULL,
    lost_rate DECIMAL(5,4) NOT NULL,
    changed_by_user_id BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_library_fee_policy_singleton CHECK (id = 1),
    CONSTRAINT chk_library_fee_policy_values CHECK
        (grace_days >= 0 AND daily_overdue_fee > 0 AND overdue_cap > 0
         AND minor_damage_rate > 0 AND minor_damage_rate <= major_damage_rate
         AND major_damage_rate <= 1 AND lost_rate > 0 AND lost_rate <= 1),
    CONSTRAINT fk_library_fee_policy_user FOREIGN KEY (changed_by_user_id) REFERENCES users(id)
);

INSERT INTO library_fee_policy
    (id, grace_days, daily_overdue_fee, overdue_cap,
     minor_damage_rate, major_damage_rate, lost_rate)
VALUES (1, 1, 0.50, 20.00, 0.2000, 0.5000, 1.0000)
ON DUPLICATE KEY UPDATE id = VALUES(id);

CREATE TABLE IF NOT EXISTS library_fee_bills (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    bill_no CHAR(12) NOT NULL UNIQUE,
    loan_id BIGINT NOT NULL UNIQUE,
    borrower_user_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    copy_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    return_condition VARCHAR(16) NOT NULL,
    damage_level VARCHAR(16) NULL,
    due_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    overdue_days INT NOT NULL,
    billable_days INT NOT NULL,
    replacement_price_snapshot DECIMAL(10,2) NULL,
    grace_days_snapshot INT NOT NULL,
    daily_overdue_fee_snapshot DECIMAL(10,2) NOT NULL,
    overdue_cap_snapshot DECIMAL(10,2) NOT NULL,
    condition_rate_snapshot DECIMAL(5,4) NULL,
    overdue_amount DECIMAL(10,2) NOT NULL,
    damage_amount DECIMAL(10,2) NOT NULL,
    lost_amount DECIMAL(10,2) NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    reason VARCHAR(255) NULL,
    created_by_user_id BIGINT NOT NULL,
    payment_operation_id CHAR(36) NULL UNIQUE,
    payment_reference_no VARCHAR(64) NULL UNIQUE,
    paid_at TIMESTAMP NULL,
    waived_by_user_id BIGINT NULL,
    waived_reason VARCHAR(255) NULL,
    waived_at TIMESTAMP NULL,
    refund_operation_id CHAR(36) NULL UNIQUE,
    refund_reference_no VARCHAR(64) NULL UNIQUE,
    refunded_by_user_id BIGINT NULL,
    refunded_reason VARCHAR(255) NULL,
    refunded_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_library_fee_owner_status (borrower_user_id, status, created_at),
    INDEX idx_library_fee_status_time (status, created_at),
    CONSTRAINT fk_library_fee_loan FOREIGN KEY (loan_id) REFERENCES library_loans(id),
    CONSTRAINT fk_library_fee_borrower FOREIGN KEY (borrower_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_book FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT fk_library_fee_copy FOREIGN KEY (copy_id) REFERENCES book_copies(id),
    CONSTRAINT fk_library_fee_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_waiver FOREIGN KEY (waived_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_fee_refunder FOREIGN KEY (refunded_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_library_fee_status CHECK (status IN ('PENDING', 'PAID', 'WAIVED', 'REFUNDED')),
    CONSTRAINT chk_library_fee_condition CHECK (return_condition IN ('NORMAL', 'DAMAGED', 'LOST')),
    CONSTRAINT chk_library_fee_damage CHECK
        ((return_condition = 'DAMAGED' AND damage_level IN ('MINOR', 'MAJOR'))
         OR (return_condition <> 'DAMAGED' AND damage_level IS NULL)),
    CONSTRAINT chk_library_fee_amounts CHECK
        (overdue_amount >= 0 AND damage_amount >= 0 AND lost_amount >= 0
         AND total_amount > 0 AND total_amount = overdue_amount + damage_amount + lost_amount)
);
