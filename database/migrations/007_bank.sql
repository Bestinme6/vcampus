USE vcampus;

CREATE TABLE IF NOT EXISTS bank_accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bank_account_user (user_id),
    INDEX idx_bank_account_status (status, id),
    CONSTRAINT fk_bank_account_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_bank_account_balance CHECK (balance >= 0.00),
    CONSTRAINT chk_bank_account_status CHECK (status IN ('ACTIVE', 'FROZEN'))
);

CREATE TABLE IF NOT EXISTS bank_ledger_entries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    balance_after DECIMAL(15,2) NOT NULL,
    reference_no VARCHAR(64) NOT NULL,
    counterparty_user_id BIGINT NULL,
    operator_user_id BIGINT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bank_ledger_business (account_id, entry_type, reference_no),
    INDEX idx_bank_ledger_account_time (account_id, created_at),
    INDEX idx_bank_ledger_reference (reference_no),
    CONSTRAINT fk_bank_ledger_account FOREIGN KEY (account_id) REFERENCES bank_accounts(id),
    CONSTRAINT fk_bank_ledger_counterparty FOREIGN KEY (counterparty_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_bank_ledger_operator FOREIGN KEY (operator_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_bank_ledger_type CHECK (entry_type IN
        ('ADMIN_TOPUP', 'TRANSFER_OUT', 'TRANSFER_IN', 'SHOP_PAYMENT', 'SHOP_REFUND')),
    CONSTRAINT chk_bank_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_bank_ledger_amount CHECK (amount > 0.00),
    CONSTRAINT chk_bank_ledger_balance CHECK (balance_after >= 0.00)
);

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'FORUM_POST_COMMENTED',
         'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED',
         'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP',
         'BANK_ACCOUNT_STATUS_CHANGED')),
    DROP CHECK chk_notification_source,
    ADD CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY', 'FORUM', 'BANK')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS',
         'FORUM_POST', 'BANK_LEDGER', 'NONE'));
