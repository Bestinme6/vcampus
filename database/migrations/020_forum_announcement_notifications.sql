ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'SCHEDULE_CHANGED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'LIBRARY_RESERVATION_AVAILABLE',
         'FORUM_POST_COMMENTED', 'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED',
         'FORUM_COMMENT_REPLIED', 'FORUM_ANNOUNCEMENT', 'FORUM_CONTENT_REPORTED',
         'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP', 'BANK_ACCOUNT_STATUS_CHANGED',
         'SHOP_ORDER_PAID', 'SHOP_ORDER_REFUNDED', 'SHOP_ORDER_SHIPPED'));

SET @vcampus_announcement_index_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'notifications'
             AND index_name = 'idx_notification_target_created'),
    'SELECT 1',
    'ALTER TABLE notifications ADD INDEX idx_notification_target_created (notification_type, related_entity_id, created_at)');
PREPARE vcampus_announcement_index_stmt FROM @vcampus_announcement_index_ddl;
EXECUTE vcampus_announcement_index_stmt;
DEALLOCATE PREPARE vcampus_announcement_index_stmt;
