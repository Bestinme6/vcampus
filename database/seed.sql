USE vcampus;

INSERT INTO roles (role_code, role_name) VALUES
    ('STUDENT', '学生'),
    ('TEACHER', '教师'),
    ('SUPER_ADMIN', '超级管理员'),
    ('STUDENT_ADMIN', '学籍管理员'),
    ('ACADEMIC_ADMIN', '教务管理员'),
    ('LIBRARY_ADMIN', '图书管理员'),
    ('SHOP_ADMIN', '商店管理员'),
    ('BANK_ADMIN', '银行管理员'),
    ('FORUM_ADMIN', '论坛管理员')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name);

INSERT INTO forum_sections (code, name, description, sort_order, enabled)
VALUES ('CAMPUS', '校园生活', '校园见闻与生活交流', 10, TRUE),
       ('STUDY', '学习广角', '课程、竞赛与学习经验', 20, TRUE),
       ('ACTIVITY', '场馆运动', '社团、活动与运动', 30, TRUE),
       ('CAREER', '生涯发展', '实习、就业与成长', 40, TRUE),
       ('MARKET', '交换认领', '闲置交换与失物招领', 50, TRUE)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

-- 不在 SQL 中保存明文默认密码。
-- 首个管理员账号应由后续的安全初始化程序创建。

INSERT INTO departments (department_code, department_name) VALUES
    ('01', '计算机科学与工程学院'),
    ('02', '经济管理学院')
ON DUPLICATE KEY UPDATE department_name = VALUES(department_name);

INSERT INTO majors (department_id, major_code, major_name)
SELECT id, '0101', '计算机科学与技术' FROM departments WHERE department_code = '01'
ON DUPLICATE KEY UPDATE major_name = VALUES(major_name);

INSERT INTO majors (department_id, major_code, major_name)
SELECT id, '0102', '软件工程' FROM departments WHERE department_code = '01'
ON DUPLICATE KEY UPDATE major_name = VALUES(major_name);

INSERT INTO majors (department_id, major_code, major_name)
SELECT id, '0201', '工商管理' FROM departments WHERE department_code = '02'
ON DUPLICATE KEY UPDATE major_name = VALUES(major_name);

INSERT INTO administrative_classes (major_id, class_code, class_name, enrollment_year)
SELECT id, '2026010101', '计算机科学与技术2026级1班', 2026 FROM majors WHERE major_code = '0101'
ON DUPLICATE KEY UPDATE class_name = VALUES(class_name);

INSERT INTO academic_terms
    (academic_year, term_number, term_name, start_date, end_date,
     selection_start, selection_end, drop_deadline, status)
VALUES
    ('2026-2027', 1, '2026-2027学年第一学期', '2026-09-01', '2027-01-15',
     '2026-08-20 00:00:00', '2026-09-10 23:59:59', '2026-09-20 23:59:59', 'SELECTION')
ON DUPLICATE KEY UPDATE
    term_name = VALUES(term_name), selection_start = VALUES(selection_start),
    selection_end = VALUES(selection_end), drop_deadline = VALUES(drop_deadline), status = VALUES(status);

INSERT INTO courses (course_code, course_name, credits, total_hours, description) VALUES
    ('C000001', 'Java程序设计', 3.0, 48, 'Java语言基础、面向对象、Swing与网络编程'),
    ('C000002', '数据库系统原理', 3.0, 48, '关系数据库、SQL、事务与数据库设计'),
    ('C000003', '高等数学', 5.0, 80, '一元函数微积分与常微分方程')
ON DUPLICATE KEY UPDATE
    course_name = VALUES(course_name), credits = VALUES(credits),
    total_hours = VALUES(total_hours), description = VALUES(description);

INSERT INTO administrative_classes (major_id, class_code, class_name, enrollment_year)
SELECT id, '2026010201', '软件工程2026级1班', 2026 FROM majors WHERE major_code = '0102'
ON DUPLICATE KEY UPDATE class_name = VALUES(class_name);

INSERT INTO administrative_classes (major_id, class_code, class_name, enrollment_year)
SELECT id, '2026020101', '工商管理2026级1班', 2026 FROM majors WHERE major_code = '0201'
ON DUPLICATE KEY UPDATE class_name = VALUES(class_name);

-- 图书馆匿名课程演示数据。书目与馆藏可重复执行；不包含密码或真实个人信息。
INSERT INTO books
    (isbn, title, authors, publisher, publish_year, category, description, enabled)
VALUES
    ('9780134685991', 'Effective Java', 'Joshua Bloch', 'Addison-Wesley', 2018, '计算机', 'Java API 设计与工程实践。', TRUE),
    ('9780132350884', 'Clean Code', 'Robert C. Martin', 'Prentice Hall', 2008, '计算机', '可读、可维护代码的实践原则。', TRUE),
    ('9780201633610', 'Design Patterns', 'Erich Gamma 等', 'Addison-Wesley', 1994, '计算机', '经典面向对象设计模式。', TRUE),
    ('9780141439518', 'Pride and Prejudice', 'Jane Austen', 'Penguin Classics', 2003, '文学', '匿名化课程演示用文学馆藏。', TRUE),
    ('9780061120084', 'To Kill a Mockingbird', 'Harper Lee', 'Harper Perennial', 2006, '文学', '匿名化课程演示用文学馆藏。', TRUE),
    ('9780060555665', 'The Intelligent Investor', 'Benjamin Graham', 'Harper Business', 2006, '经济', '价值投资基础读物。', TRUE)
ON DUPLICATE KEY UPDATE
    title = VALUES(title), authors = VALUES(authors), publisher = VALUES(publisher),
    publish_year = VALUES(publish_year), category = VALUES(category),
    description = VALUES(description), enabled = VALUES(enabled);

INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000101', 'A-01-01', 'AVAILABLE' FROM books WHERE isbn = '9780134685991'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000102', 'A-01-02', 'AVAILABLE' FROM books WHERE isbn = '9780134685991'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000103', 'A-02-01', 'AVAILABLE' FROM books WHERE isbn = '9780132350884'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000104', 'A-02-02', 'AVAILABLE' FROM books WHERE isbn = '9780132350884'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000105', 'A-03-01', 'AVAILABLE' FROM books WHERE isbn = '9780201633610'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000106', 'B-01-01', 'AVAILABLE' FROM books WHERE isbn = '9780141439518'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000107', 'B-01-02', 'AVAILABLE' FROM books WHERE isbn = '9780141439518'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000108', 'B-02-01', 'AVAILABLE' FROM books WHERE isbn = '9780061120084'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000109', 'C-01-01', 'AVAILABLE' FROM books WHERE isbn = '9780060555665'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);
INSERT INTO book_copies (book_id, barcode, shelf_location, status)
SELECT id, 'B000000110', 'C-01-02', 'AVAILABLE' FROM books WHERE isbn = '9780060555665'
ON DUPLICATE KEY UPDATE shelf_location = VALUES(shelf_location);

-- 先通过账号管理创建匿名演示账号 2026000001、2026000002 和 T0000001，
-- 再重新执行本脚本，即可生成活动、即将到期、逾期和已归还四类记录。
INSERT INTO library_loans
    (copy_id, borrower_user_id, borrowed_at, initial_due_at, due_at,
     renewal_count, returned_at, return_condition, channel,
     checkout_operator_user_id, return_operator_user_id)
SELECT c.id, u.id, CURRENT_TIMESTAMP - INTERVAL 10 DAY,
       CURRENT_TIMESTAMP + INTERVAL 20 DAY, CURRENT_TIMESTAMP + INTERVAL 20 DAY,
       0, NULL, NULL, 'SELF_SERVICE', u.id, NULL
FROM book_copies c JOIN users u ON u.username = '2026000001'
WHERE c.barcode = 'B000000101'
  AND NOT EXISTS (SELECT 1 FROM library_loans l WHERE l.copy_id = c.id);

INSERT INTO library_loans
    (copy_id, borrower_user_id, borrowed_at, initial_due_at, due_at,
     renewal_count, returned_at, return_condition, channel,
     checkout_operator_user_id, return_operator_user_id)
SELECT c.id, u.id, CURRENT_TIMESTAMP - INTERVAL 57 DAY,
       CURRENT_TIMESTAMP + INTERVAL 3 DAY, CURRENT_TIMESTAMP + INTERVAL 3 DAY,
       0, NULL, NULL, 'ADMIN_DESK', u.id, NULL
FROM book_copies c JOIN users u ON u.username = 'T0000001'
WHERE c.barcode = 'B000000103'
  AND NOT EXISTS (SELECT 1 FROM library_loans l WHERE l.copy_id = c.id);

INSERT INTO library_loans
    (copy_id, borrower_user_id, borrowed_at, initial_due_at, due_at,
     renewal_count, returned_at, return_condition, channel,
     checkout_operator_user_id, return_operator_user_id)
SELECT c.id, u.id, CURRENT_TIMESTAMP - INTERVAL 40 DAY,
       CURRENT_TIMESTAMP - INTERVAL 10 DAY, CURRENT_TIMESTAMP - INTERVAL 10 DAY,
       0, NULL, NULL, 'SELF_SERVICE', u.id, NULL
FROM book_copies c JOIN users u ON u.username = '2026000002'
WHERE c.barcode = 'B000000105'
  AND NOT EXISTS (SELECT 1 FROM library_loans l WHERE l.copy_id = c.id);

INSERT INTO library_loans
    (copy_id, borrower_user_id, borrowed_at, initial_due_at, due_at,
     renewal_count, returned_at, return_condition, channel,
     checkout_operator_user_id, return_operator_user_id)
SELECT c.id, u.id, CURRENT_TIMESTAMP - INTERVAL 70 DAY,
       CURRENT_TIMESTAMP - INTERVAL 40 DAY, CURRENT_TIMESTAMP - INTERVAL 40 DAY,
       0, CURRENT_TIMESTAMP - INTERVAL 35 DAY, 'NORMAL', 'SELF_SERVICE', u.id, u.id
FROM book_copies c JOIN users u ON u.username = '2026000001'
WHERE c.barcode = 'B000000106'
  AND NOT EXISTS (SELECT 1 FROM library_loans l WHERE l.copy_id = c.id);

UPDATE book_copies c
SET c.status = CASE
        WHEN EXISTS (SELECT 1 FROM library_loans l
                     WHERE l.copy_id = c.id AND l.returned_at IS NULL) THEN 'ON_LOAN'
        ELSE 'AVAILABLE'
    END,
    c.status_reason = NULL
WHERE c.barcode BETWEEN 'B000000101' AND 'B000000110';
