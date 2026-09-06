-- Apply after 013. Adds versioned curricula, section targets and schedule revisions.
USE vcampus;

CREATE TABLE IF NOT EXISTS curriculum_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    major_id BIGINT NOT NULL,
    plan_name VARCHAR(120) NOT NULL,
    version_no INT NOT NULL,
    enrollment_year_start SMALLINT NOT NULL,
    enrollment_year_end SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by_user_id BIGINT NULL,
    published_at TIMESTAMP NULL,
    UNIQUE KEY uk_curriculum_major_version (major_id, version_no),
    INDEX idx_curriculum_match
        (major_id, status, enrollment_year_start, enrollment_year_end),
    CONSTRAINT fk_curriculum_major FOREIGN KEY (major_id) REFERENCES majors(id),
    CONSTRAINT fk_curriculum_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_curriculum_publisher FOREIGN KEY (published_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_curriculum_years CHECK
        (enrollment_year_start BETWEEN 2000 AND 2100
         AND enrollment_year_end BETWEEN enrollment_year_start AND 2100),
    CONSTRAINT chk_curriculum_status CHECK
        (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_curriculum_publish_audit CHECK
        ((status = 'DRAFT' AND published_by_user_id IS NULL AND published_at IS NULL)
         OR (status IN ('PUBLISHED', 'ARCHIVED')
             AND published_by_user_id IS NOT NULL AND published_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS curriculum_plan_courses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    requirement_type VARCHAR(16) NOT NULL,
    recommended_term_number TINYINT NOT NULL,
    UNIQUE KEY uk_curriculum_plan_course (plan_id, course_id),
    INDEX idx_curriculum_course (course_id, plan_id),
    CONSTRAINT fk_curriculum_course_plan FOREIGN KEY (plan_id)
        REFERENCES curriculum_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_curriculum_course_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT chk_curriculum_requirement CHECK
        (requirement_type IN ('REQUIRED', 'ELECTIVE')),
    CONSTRAINT chk_curriculum_recommended_term CHECK
        (recommended_term_number BETWEEN 1 AND 12)
);

CREATE TABLE IF NOT EXISTS course_section_targets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    major_id BIGINT NOT NULL,
    enrollment_year_start SMALLINT NOT NULL,
    enrollment_year_end SMALLINT NOT NULL,
    UNIQUE KEY uk_section_target_range
        (section_id, major_id, enrollment_year_start, enrollment_year_end),
    INDEX idx_section_target_match
        (major_id, enrollment_year_start, enrollment_year_end, section_id),
    CONSTRAINT fk_section_target_section FOREIGN KEY (section_id)
        REFERENCES course_sections(id) ON DELETE CASCADE,
    CONSTRAINT fk_section_target_major FOREIGN KEY (major_id) REFERENCES majors(id),
    CONSTRAINT chk_section_target_years CHECK
        (enrollment_year_start BETWEEN 2000 AND 2100
         AND enrollment_year_end BETWEEN enrollment_year_start AND 2100)
);

CREATE TABLE IF NOT EXISTS course_section_schedule_revisions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by_user_id BIGINT NULL,
    published_at TIMESTAMP NULL,
    UNIQUE KEY uk_schedule_section_revision (section_id, revision_no),
    UNIQUE KEY uk_schedule_revision_identity (id, section_id),
    INDEX idx_schedule_revision_status (section_id, status),
    CONSTRAINT fk_schedule_revision_section FOREIGN KEY (section_id)
        REFERENCES course_sections(id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_revision_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users(id),
    CONSTRAINT fk_schedule_revision_publisher FOREIGN KEY (published_by_user_id)
        REFERENCES users(id),
    CONSTRAINT chk_schedule_revision_number CHECK (revision_no > 0),
    CONSTRAINT chk_schedule_revision_status CHECK
        (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT chk_schedule_revision_publish_audit CHECK
        ((status = 'DRAFT' AND published_by_user_id IS NULL AND published_at IS NULL)
         OR (status IN ('PUBLISHED', 'SUPERSEDED')
             AND published_by_user_id IS NOT NULL AND published_at IS NOT NULL))
);

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'SCHEDULE_CHANGED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'LIBRARY_RESERVATION_AVAILABLE',
         'FORUM_POST_COMMENTED', 'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED',
         'FORUM_COMMENT_REPLIED', 'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP',
         'BANK_ACCOUNT_STATUS_CHANGED', 'SHOP_ORDER_PAID', 'SHOP_ORDER_REFUNDED',
         'SHOP_ORDER_SHIPPED')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'ACADEMIC_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE',
         'LIBRARY_LOANS', 'LIBRARY_CATALOG', 'FORUM_POST', 'BANK_LEDGER',
         'SHOP_ORDERS', 'NONE'));

SET @vcampus_academic_ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'class_schedules'
           AND column_name = 'revision_id'
    ),
    'SELECT 1',
    'ALTER TABLE class_schedules ADD COLUMN revision_id BIGINT NULL AFTER section_id'
);
PREPARE vcampus_academic_upgrade FROM @vcampus_academic_ddl;
EXECUTE vcampus_academic_upgrade;
DEALLOCATE PREPARE vcampus_academic_upgrade;

INSERT INTO course_section_schedule_revisions
    (section_id, revision_no, status, created_by_user_id,
     published_by_user_id, published_at)
SELECT DISTINCT s.id, 1, 'PUBLISHED', s.teacher_user_id,
       s.teacher_user_id, CURRENT_TIMESTAMP
  FROM course_sections s
  JOIN class_schedules cs ON cs.section_id = s.id
ON DUPLICATE KEY UPDATE section_id = VALUES(section_id);

UPDATE class_schedules cs
JOIN course_section_schedule_revisions revision
  ON revision.section_id = cs.section_id
 AND revision.revision_no = 1
SET cs.revision_id = revision.id
WHERE cs.revision_id IS NULL;

SET @vcampus_academic_ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'class_schedules'
           AND column_name = 'revision_id'
           AND is_nullable = 'YES'
    ),
    'ALTER TABLE class_schedules MODIFY COLUMN revision_id BIGINT NOT NULL',
    'SELECT 1'
);
PREPARE vcampus_academic_upgrade FROM @vcampus_academic_ddl;
EXECUTE vcampus_academic_upgrade;
DEALLOCATE PREPARE vcampus_academic_upgrade;

SET @vcampus_academic_ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
         WHERE table_schema = DATABASE()
           AND table_name = 'class_schedules'
           AND index_name = 'idx_schedule_revision'
    ),
    'SELECT 1',
    'ALTER TABLE class_schedules ADD INDEX idx_schedule_revision (revision_id, section_id)'
);
PREPARE vcampus_academic_upgrade FROM @vcampus_academic_ddl;
EXECUTE vcampus_academic_upgrade;
DEALLOCATE PREPARE vcampus_academic_upgrade;

SET @vcampus_academic_ddl = IF(
    EXISTS(
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_schema = DATABASE()
           AND table_name = 'class_schedules'
           AND constraint_name = 'fk_schedule_revision_identity'
           AND constraint_type = 'FOREIGN KEY'
    ),
    'SELECT 1',
    'ALTER TABLE class_schedules ADD CONSTRAINT fk_schedule_revision_identity FOREIGN KEY (revision_id, section_id) REFERENCES course_section_schedule_revisions(id, section_id)'
);
PREPARE vcampus_academic_upgrade FROM @vcampus_academic_ddl;
EXECUTE vcampus_academic_upgrade;
DEALLOCATE PREPARE vcampus_academic_upgrade;
