USE vcampus;

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    DROP CHECK chk_notification_source,
    DROP CHECK chk_notification_target;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE')),
    ADD CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY')),
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS', 'NONE'));

CREATE TABLE IF NOT EXISTS books (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    isbn VARCHAR(20) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    authors VARCHAR(300) NOT NULL,
    publisher VARCHAR(160) NOT NULL,
    publish_year SMALLINT NULL,
    category VARCHAR(80) NOT NULL,
    description VARCHAR(1000) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_book_title (title),
    INDEX idx_book_category_enabled (category, enabled),
    CONSTRAINT chk_book_publish_year CHECK (publish_year IS NULL OR publish_year BETWEEN 1000 AND 9999)
);

CREATE TABLE IF NOT EXISTS book_copies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    book_id BIGINT NOT NULL,
    barcode CHAR(10) NOT NULL UNIQUE,
    shelf_location VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    status_reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_copy_book_status (book_id, status),
    CONSTRAINT fk_copy_book FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT chk_copy_barcode CHECK (barcode REGEXP '^B[0-9]{9}$'),
    CONSTRAINT chk_copy_status CHECK (status IN
        ('AVAILABLE', 'ON_LOAN', 'LOST', 'DAMAGED', 'WITHDRAWN'))
);

CREATE TABLE IF NOT EXISTS library_loans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    copy_id BIGINT NOT NULL,
    borrower_user_id BIGINT NOT NULL,
    borrowed_at TIMESTAMP NOT NULL,
    initial_due_at TIMESTAMP NOT NULL,
    due_at TIMESTAMP NOT NULL,
    renewal_count TINYINT NOT NULL DEFAULT 0,
    returned_at TIMESTAMP NULL,
    return_condition VARCHAR(16) NULL,
    channel VARCHAR(16) NOT NULL,
    checkout_operator_user_id BIGINT NOT NULL,
    return_operator_user_id BIGINT NULL,
    due_notice_sent_at TIMESTAMP NULL,
    overdue_notice_sent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_copy_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN returned_at IS NULL THEN copy_id ELSE NULL END) STORED,
    UNIQUE KEY uk_library_active_copy (active_copy_id),
    INDEX idx_library_borrower_active_due (borrower_user_id, returned_at, due_at),
    INDEX idx_library_copy_borrowed (copy_id, borrowed_at),
    CONSTRAINT fk_library_loan_copy FOREIGN KEY (copy_id) REFERENCES book_copies(id),
    CONSTRAINT fk_library_loan_borrower FOREIGN KEY (borrower_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_loan_checkout_operator FOREIGN KEY (checkout_operator_user_id) REFERENCES users(id),
    CONSTRAINT fk_library_loan_return_operator FOREIGN KEY (return_operator_user_id) REFERENCES users(id),
    CONSTRAINT chk_library_renewal_count CHECK (renewal_count BETWEEN 0 AND 1),
    CONSTRAINT chk_library_return_condition CHECK
        (return_condition IS NULL OR return_condition IN ('NORMAL', 'LOST')),
    CONSTRAINT chk_library_channel CHECK (channel IN ('SELF_SERVICE', 'ADMIN_DESK')),
    CONSTRAINT chk_library_due_dates CHECK (borrowed_at <= initial_due_at AND initial_due_at <= due_at),
    CONSTRAINT chk_library_closed_state CHECK
        ((returned_at IS NULL AND return_condition IS NULL AND return_operator_user_id IS NULL)
         OR (returned_at IS NOT NULL AND return_condition IS NOT NULL AND return_operator_user_id IS NOT NULL))
);
