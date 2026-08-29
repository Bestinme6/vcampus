USE vcampus;

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'FORUM_POST_COMMENTED',
         'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED')),
    DROP CHECK chk_notification_source,
    ADD CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY', 'FORUM')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS',
         'FORUM_POST', 'NONE'));
