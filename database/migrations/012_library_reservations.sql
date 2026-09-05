-- Apply after 011. Preserves existing catalog, copies, loans and notifications.
USE vcampus;

CREATE TABLE IF NOT EXISTS library_reservations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    book_id BIGINT NOT NULL,
    borrower_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notified_at TIMESTAMP NULL,
    active_book_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'WAITING' THEN book_id ELSE NULL END) STORED,
    UNIQUE KEY uk_library_reservation_active (borrower_user_id, active_book_id),
    INDEX idx_library_reservation_book_status (book_id, status, id),
    INDEX idx_library_reservation_borrower_created (borrower_user_id, created_at, id),
    CONSTRAINT fk_library_reservation_book FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT fk_library_reservation_borrower FOREIGN KEY (borrower_user_id) REFERENCES users(id),
    CONSTRAINT chk_library_reservation_status CHECK (status IN ('WAITING', 'NOTIFIED', 'CANCELLED')),
    CONSTRAINT chk_library_reservation_notice CHECK
        ((status = 'NOTIFIED' AND notified_at IS NOT NULL) OR (status <> 'NOTIFIED' AND notified_at IS NULL))
);

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'LIBRARY_RESERVATION_AVAILABLE',
         'FORUM_POST_COMMENTED', 'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED', 'FORUM_COMMENT_REPLIED',
         'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP', 'BANK_ACCOUNT_STATUS_CHANGED',
         'SHOP_ORDER_PAID', 'SHOP_ORDER_REFUNDED', 'SHOP_ORDER_SHIPPED')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS',
         'LIBRARY_CATALOG', 'FORUM_POST', 'BANK_LEDGER', 'SHOP_ORDERS', 'NONE'));
