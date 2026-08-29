CREATE DATABASE IF NOT EXISTS vcampus
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE vcampus;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    password_salt VARCHAR(128) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    force_password_change BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

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
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'FORUM_POST_COMMENTED',
         'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED')),
    CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY', 'FORUM')),
    CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS',
         'FORUM_POST', 'NONE'))
);

-- CREATE TABLE IF NOT EXISTS does not update checks on an existing table.
-- Rebuild the named notification checks so rerunning schema.sql upgrades them too.
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

CREATE TABLE IF NOT EXISTS roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    action_code VARCHAR(128) NOT NULL,
    result_code VARCHAR(32) NOT NULL,
    client_address VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_user_time (user_id, created_at),
    CONSTRAINT fk_audit_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS departments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_code CHAR(2) NOT NULL UNIQUE,
    department_name VARCHAR(100) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS majors (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    department_id BIGINT NOT NULL,
    major_code CHAR(4) NOT NULL UNIQUE,
    major_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uk_major_department_name (department_id, major_name),
    CONSTRAINT fk_major_department
        FOREIGN KEY (department_id) REFERENCES departments(id)
);

CREATE TABLE IF NOT EXISTS administrative_classes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    major_id BIGINT NOT NULL,
    class_code VARCHAR(16) NOT NULL UNIQUE,
    class_name VARCHAR(100) NOT NULL,
    enrollment_year SMALLINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_class_major_year (major_id, enrollment_year),
    CONSTRAINT fk_class_major
        FOREIGN KEY (major_id) REFERENCES majors(id)
);

CREATE TABLE IF NOT EXISTS student_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    student_number CHAR(10) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    gender VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED',
    birth_date DATE NULL,
    department_id BIGINT NOT NULL,
    major_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    enrollment_year SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENROLLED',
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    address VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_student_name (full_name),
    INDEX idx_student_status (status),
    INDEX idx_student_class (class_id),
    CONSTRAINT fk_student_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_student_department
        FOREIGN KEY (department_id) REFERENCES departments(id),
    CONSTRAINT fk_student_major
        FOREIGN KEY (major_id) REFERENCES majors(id),
    CONSTRAINT fk_student_class
        FOREIGN KEY (class_id) REFERENCES administrative_classes(id),
    CONSTRAINT chk_student_gender
        CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNSPECIFIED')),
    CONSTRAINT chk_student_status
        CHECK (status IN ('ENROLLED', 'SUSPENDED', 'WITHDRAWN', 'GRADUATED')),
    CONSTRAINT chk_student_number
        CHECK (student_number REGEXP '^[0-9]{10}$')
);

CREATE TABLE IF NOT EXISTS teacher_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    teacher_number VARCHAR(32) NOT NULL UNIQUE,
    full_name VARCHAR(100) NOT NULL,
    department_id BIGINT NOT NULL,
    professional_title VARCHAR(100) NOT NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_teacher_user
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_teacher_department
        FOREIGN KEY (department_id) REFERENCES departments(id)
);

CREATE TABLE IF NOT EXISTS student_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    old_status VARCHAR(16) NULL,
    new_status VARCHAR(16) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_student_status_history (student_id, changed_at),
    CONSTRAINT fk_status_history_student
        FOREIGN KEY (student_id) REFERENCES student_profiles(id),
    CONSTRAINT fk_status_history_operator
        FOREIGN KEY (changed_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_old_student_status
        CHECK (old_status IS NULL OR old_status IN ('ENROLLED', 'SUSPENDED', 'WITHDRAWN', 'GRADUATED')),
    CONSTRAINT chk_new_student_status
        CHECK (new_status IN ('ENROLLED', 'SUSPENDED', 'WITHDRAWN', 'GRADUATED'))
);

CREATE TABLE IF NOT EXISTS academic_terms (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    academic_year VARCHAR(9) NOT NULL,
    term_number TINYINT NOT NULL,
    term_name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    selection_start TIMESTAMP NOT NULL,
    selection_end TIMESTAMP NOT NULL,
    drop_deadline TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    UNIQUE KEY uk_academic_term (academic_year, term_number),
    CONSTRAINT chk_term_number CHECK (term_number IN (1, 2, 3)),
    CONSTRAINT chk_term_dates CHECK (start_date <= end_date),
    CONSTRAINT chk_selection_dates CHECK (selection_start <= selection_end AND selection_end <= drop_deadline),
    CONSTRAINT chk_term_status CHECK (status IN ('PLANNED', 'SELECTION', 'IN_PROGRESS', 'FINISHED'))
);

CREATE TABLE IF NOT EXISTS courses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    course_code CHAR(7) NOT NULL UNIQUE,
    course_name VARCHAR(120) NOT NULL,
    credits DECIMAL(3,1) NOT NULL,
    total_hours SMALLINT NOT NULL,
    description VARCHAR(500) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_course_name (course_name),
    CONSTRAINT chk_course_code CHECK (course_code REGEXP '^C[0-9]{6}$'),
    CONSTRAINT chk_course_credits CHECK (credits > 0 AND credits <= 20),
    CONSTRAINT chk_course_hours CHECK (total_hours > 0 AND total_hours <= 400)
);

CREATE TABLE IF NOT EXISTS course_sections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    section_code VARCHAR(24) NOT NULL UNIQUE,
    teacher_user_id BIGINT NOT NULL,
    capacity SMALLINT NOT NULL,
    enrolled_count SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    grades_published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_section_term_course (term_id, course_id),
    INDEX idx_section_teacher (teacher_user_id, term_id),
    CONSTRAINT fk_section_term FOREIGN KEY (term_id) REFERENCES academic_terms(id),
    CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_section_teacher FOREIGN KEY (teacher_user_id) REFERENCES users(id),
    CONSTRAINT chk_section_capacity CHECK (capacity > 0 AND capacity <= 500),
    CONSTRAINT chk_section_count CHECK (enrolled_count >= 0 AND enrolled_count <= capacity),
    CONSTRAINT chk_section_status CHECK (status IN ('PLANNED', 'OPEN', 'CLOSED', 'COMPLETED'))
);

CREATE TABLE IF NOT EXISTS class_schedules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL,
    start_period TINYINT NOT NULL,
    end_period TINYINT NOT NULL,
    start_week TINYINT NOT NULL,
    end_week TINYINT NOT NULL,
    classroom VARCHAR(100) NOT NULL,
    INDEX idx_schedule_section (section_id),
    INDEX idx_schedule_time (day_of_week, start_period, end_period),
    CONSTRAINT fk_schedule_section FOREIGN KEY (section_id) REFERENCES course_sections(id) ON DELETE CASCADE,
    CONSTRAINT chk_schedule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_schedule_period CHECK (start_period BETWEEN 1 AND 12 AND end_period BETWEEN start_period AND 12),
    CONSTRAINT chk_schedule_week CHECK (start_week BETWEEN 1 AND 30 AND end_week BETWEEN start_week AND 30)
);

CREATE TABLE IF NOT EXISTS course_enrollments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENROLLED',
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dropped_at TIMESTAMP NULL,
    UNIQUE KEY uk_section_student (section_id, student_id),
    INDEX idx_enrollment_student_status (student_id, status),
    CONSTRAINT fk_enrollment_section FOREIGN KEY (section_id) REFERENCES course_sections(id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_id) REFERENCES student_profiles(id),
    CONSTRAINT chk_enrollment_status CHECK (status IN ('ENROLLED', 'DROPPED'))
);

CREATE TABLE IF NOT EXISTS grades (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    enrollment_id BIGINT NOT NULL UNIQUE,
    score DECIMAL(5,2) NOT NULL,
    grade_point DECIMAL(3,1) NOT NULL,
    comment VARCHAR(255) NULL,
    submitted_by_user_id BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    CONSTRAINT fk_grade_enrollment FOREIGN KEY (enrollment_id) REFERENCES course_enrollments(id),
    CONSTRAINT fk_grade_submitter FOREIGN KEY (submitted_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_grade_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT chk_grade_point CHECK (grade_point BETWEEN 0 AND 4)
);

CREATE TABLE IF NOT EXISTS grade_change_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    grade_id BIGINT NOT NULL,
    old_score DECIMAL(5,2) NULL,
    new_score DECIMAL(5,2) NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(255) NOT NULL,
    INDEX idx_grade_history (grade_id, changed_at),
    CONSTRAINT fk_grade_history_grade FOREIGN KEY (grade_id) REFERENCES grades(id),
    CONSTRAINT fk_grade_history_user FOREIGN KEY (changed_by_user_id) REFERENCES users(id)
);

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

CREATE TABLE IF NOT EXISTS forum_sections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_forum_section_enabled_sort (enabled, sort_order, id),
    CONSTRAINT fk_forum_section_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS forum_posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    view_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_commented_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,
    INDEX idx_forum_post_feed
        (section_id, status, pinned, last_commented_at, created_at),
    INDEX idx_forum_post_author (author_user_id, created_at),
    CONSTRAINT fk_forum_post_section FOREIGN KEY (section_id) REFERENCES forum_sections(id),
    CONSTRAINT fk_forum_post_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_post_status CHECK (status IN ('NORMAL', 'DELETED', 'HIDDEN')),
    CONSTRAINT chk_forum_post_counts CHECK (view_count >= 0 AND comment_count >= 0)
);

CREATE TABLE IF NOT EXISTS forum_comments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_forum_comment_timeline (post_id, status, created_at, id),
    INDEX idx_forum_comment_author (author_user_id, created_at),
    CONSTRAINT fk_forum_comment_post FOREIGN KEY (post_id) REFERENCES forum_posts(id),
    CONSTRAINT fk_forum_comment_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_comment_status CHECK (status IN ('NORMAL', 'DELETED', 'HIDDEN'))
);

CREATE TABLE IF NOT EXISTS forum_moderation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_user_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_forum_moderation_target (target_type, target_id, created_at),
    INDEX idx_forum_moderation_operator (operator_user_id, created_at),
    CONSTRAINT fk_forum_moderation_operator FOREIGN KEY (operator_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_moderation_target
        CHECK (target_type IN ('SECTION', 'POST', 'COMMENT'))
);
