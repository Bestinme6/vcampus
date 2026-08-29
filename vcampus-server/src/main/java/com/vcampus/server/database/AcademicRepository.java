package com.vcampus.server.database;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.CourseSectionStatus;
import com.vcampus.common.model.EnrollmentStatus;
import com.vcampus.common.model.GradePolicy;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.common.model.UserRole;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AcademicRepository {
    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;

    public AcademicRepository(
            ConnectionFactory connectionFactory,
            NotificationWriter notifications) {
        this.connectionFactory = connectionFactory;
        this.notifications = notifications;
    }

    public AcademicReferences references() throws SQLException {
        List<TermReference> terms = new ArrayList<>();
        List<CourseReference> courses = new ArrayList<>();
        List<TeacherReference> teachers = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, term_name, status
                      FROM academic_terms
                     ORDER BY start_date DESC
                     LIMIT 10
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    terms.add(new TermReference(
                            result.getLong("id"), result.getString("term_name"),
                            AcademicTermStatus.valueOf(result.getString("status"))));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, course_code, course_name, credits
                      FROM courses
                     WHERE enabled = TRUE
                     ORDER BY course_code
                     LIMIT 40
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    courses.add(new CourseReference(
                            result.getLong("id"), result.getString("course_code"),
                            result.getString("course_name"), result.getBigDecimal("credits")));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT u.id, u.username, u.display_name
                      FROM users u
                      JOIN user_roles ur ON ur.user_id = u.id
                      JOIN roles r ON r.id = ur.role_id
                     WHERE u.enabled = TRUE AND r.role_code IN ('TEACHER', 'SUPER_ADMIN')
                     ORDER BY u.username
                     LIMIT 30
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    teachers.add(new TeacherReference(
                            result.getLong("id"), result.getString("username"),
                            result.getString("display_name")));
                }
            }
        }
        return new AcademicReferences(List.copyOf(terms), List.copyOf(courses), List.copyOf(teachers));
    }

    public CoursePage searchCourses(String keyword, int page, int pageSize) throws SQLException {
        String normalized = keyword == null ? "" : keyword.trim();
        String pattern = "%" + normalized + "%";
        String where = " WHERE (? = '' OR course_code LIKE ? OR course_name LIKE ?)";
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM courses" + where)) {
                bindCourseSearch(count, normalized, pattern);
                try (ResultSet result = count.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<CourseRecord> rows = new ArrayList<>();
            String sql = "SELECT id, course_code, course_name, credits, total_hours, description, enabled"
                    + " FROM courses" + where + " ORDER BY course_code LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindCourseSearch(statement, normalized, pattern);
                statement.setInt(4, pageSize);
                statement.setInt(5, (page - 1) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new CourseRecord(
                                result.getLong("id"), result.getString("course_code"),
                                result.getString("course_name"), result.getBigDecimal("credits"),
                                result.getInt("total_hours"), result.getString("description"),
                                result.getBoolean("enabled")));
                    }
                }
            }
            return new CoursePage(List.copyOf(rows), page, pageSize, total);
        }
    }

    public long createCourse(CreateCourse command) throws SQLException {
        String sql = """
                INSERT INTO courses (course_code, course_name, credits, total_hours, description, enabled)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, command.courseCode());
            statement.setString(2, command.courseName());
            statement.setBigDecimal(3, command.credits());
            statement.setInt(4, command.totalHours());
            setNullableString(statement, 5, command.description());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return the new course id");
                }
                return keys.getLong(1);
            }
        }
    }

    public boolean updateCourse(UpdateCourse command) throws SQLException {
        String sql = """
                UPDATE courses
                   SET course_name = ?, credits = ?, total_hours = ?, description = ?, enabled = ?
                 WHERE id = ?
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, command.courseName());
            statement.setBigDecimal(2, command.credits());
            statement.setInt(3, command.totalHours());
            setNullableString(statement, 4, command.description());
            statement.setBoolean(5, command.enabled());
            statement.setLong(6, command.courseId());
            return statement.executeUpdate() == 1;
        }
    }

    public SectionPage searchSections(long termId, String keyword, int page, int pageSize) throws SQLException {
        String normalized = keyword == null ? "" : keyword.trim();
        String pattern = "%" + normalized + "%";
        String where = " WHERE (? = 0 OR cs.term_id = ?)"
                + " AND (? = '' OR c.course_code LIKE ? OR c.course_name LIKE ? OR cs.section_code LIKE ?)";
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            String countSql = """
                    SELECT COUNT(*)
                      FROM course_sections cs
                      JOIN courses c ON c.id = cs.course_id
                    """ + where;
            try (PreparedStatement count = connection.prepareStatement(countSql)) {
                bindSectionSearch(count, termId, normalized, pattern);
                try (ResultSet result = count.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            String dataSql = sectionSelect() + where
                    + " ORDER BY t.start_date DESC, c.course_code, cs.section_code LIMIT ? OFFSET ?";
            List<SectionRecord> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(dataSql)) {
                bindSectionSearch(statement, termId, normalized, pattern);
                statement.setInt(7, pageSize);
                statement.setInt(8, (page - 1) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(readSection(result));
                    }
                }
            }
            return new SectionPage(List.copyOf(rows), page, pageSize, total);
        }
    }

    public long createSection(
            CreateSection command,
            long operatorUserId,
            String operatorDisplayName) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                validateSectionReferences(connection, command);
                validateScheduleAvailability(connection, command);
                long sectionId;
                String sectionSql = """
                        INSERT INTO course_sections
                            (term_id, course_id, section_code, teacher_user_id, capacity, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(
                        sectionSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, command.termId());
                    statement.setLong(2, command.courseId());
                    statement.setString(3, command.sectionCode());
                    statement.setLong(4, command.teacherUserId());
                    statement.setInt(5, command.capacity());
                    statement.setString(6, command.status().name());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Database did not return the new section id");
                        }
                        sectionId = keys.getLong(1);
                    }
                }
                String scheduleSql = """
                        INSERT INTO class_schedules
                            (section_id, day_of_week, start_period, end_period,
                             start_week, end_week, classroom)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(scheduleSql)) {
                    for (ScheduleSlot slot : command.schedules()) {
                        statement.setLong(1, sectionId);
                        statement.setInt(2, slot.dayOfWeek());
                        statement.setInt(3, slot.startPeriod());
                        statement.setInt(4, slot.endPeriod());
                        statement.setInt(5, slot.startWeek());
                        statement.setInt(6, slot.endWeek());
                        statement.setString(7, slot.classroom());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                String courseName = courseName(connection, command.courseId());
                notifications.insert(connection, new NotificationDraft(
                        command.teacherUserId(), operatorUserId,
                        NotificationType.SCHEDULE_ASSIGNED, NotificationSource.ACADEMIC,
                        "课表安排通知",
                        operatorDisplayName + "已为您安排《" + courseName
                                + "》教学班，请查看教师课表。",
                        NotificationTarget.TEACHER_SCHEDULE, sectionId));
                connection.commit();
                return sectionId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public boolean setSectionStatus(long sectionId, CourseSectionStatus status) throws SQLException {
        String sql = """
                UPDATE course_sections
                   SET status = ?
                 WHERE id = ? AND (grades_published = FALSE OR ? <> 'OPEN')
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setLong(2, sectionId);
            statement.setString(3, status.name());
            return statement.executeUpdate() == 1;
        }
    }

    public List<SectionRecord> availableSections(long userId, long termId) throws SQLException {
        long studentId = requireStudentId(userId);
        String sql = sectionSelect(true) + """
                 WHERE cs.term_id = ? AND cs.status = 'OPEN' AND c.enabled = TRUE
                 ORDER BY c.course_code, cs.section_code
                 LIMIT 50
                """;
        List<SectionRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            statement.setLong(2, termId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SectionRecord base = readSection(result);
                    rows.add(base.withEnrollment(
                            result.getObject("own_enrollment_id") == null ? null : result.getLong("own_enrollment_id"),
                            result.getString("own_enrollment_status")));
                }
            }
        }
        return List.copyOf(rows);
    }

    public void enroll(long userId, long sectionId) throws SQLException {
        long studentId = requireStudentId(userId);
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                EnrollmentContext context = lockEnrollmentContext(connection, sectionId);
                validateSelectionWindow(context);
                if (context.enrolledCount() >= context.capacity()) {
                    throw new AcademicRuleException("教学班人数已满");
                }
                if (hasSameCourse(connection, studentId, context.termId(), context.courseId())) {
                    throw new AcademicRuleException("同一学期不能重复选择相同课程");
                }
                if (hasScheduleConflict(connection, studentId, sectionId)) {
                    throw new AcademicRuleException("该课程与已选课程时间冲突");
                }
                String sql = """
                        INSERT INTO course_enrollments (section_id, student_id, status, enrolled_at, dropped_at)
                        VALUES (?, ?, 'ENROLLED', CURRENT_TIMESTAMP, NULL)
                        ON DUPLICATE KEY UPDATE
                            status = 'ENROLLED', enrolled_at = CURRENT_TIMESTAMP, dropped_at = NULL
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, sectionId);
                    statement.setLong(2, studentId);
                    statement.executeUpdate();
                }
                updateEnrollmentCount(connection, sectionId, 1);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public void drop(long userId, long sectionId) throws SQLException {
        long studentId = requireStudentId(userId);
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String lockSql = """
                        SELECT e.id, e.status, t.drop_deadline, cs.grades_published
                          FROM course_enrollments e
                          JOIN course_sections cs ON cs.id = e.section_id
                          JOIN academic_terms t ON t.id = cs.term_id
                         WHERE e.section_id = ? AND e.student_id = ?
                         FOR UPDATE
                        """;
                try (PreparedStatement statement = connection.prepareStatement(lockSql)) {
                    statement.setLong(1, sectionId);
                    statement.setLong(2, studentId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !EnrollmentStatus.ENROLLED.name().equals(result.getString("status"))) {
                            throw new AcademicRuleException("没有找到有效的选课记录");
                        }
                        if (result.getBoolean("grades_published")) {
                            throw new AcademicRuleException("成绩已经发布，不能退课");
                        }
                        if (Instant.now().isAfter(result.getTimestamp("drop_deadline").toInstant())) {
                            throw new AcademicRuleException("已经超过退课截止时间");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE course_enrollments
                           SET status = 'DROPPED', dropped_at = CURRENT_TIMESTAMP
                         WHERE section_id = ? AND student_id = ?
                        """)) {
                    statement.setLong(1, sectionId);
                    statement.setLong(2, studentId);
                    statement.executeUpdate();
                }
                updateEnrollmentCount(connection, sectionId, -1);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public List<ScheduleRecord> mySchedule(long userId, long termId) throws SQLException {
        long studentId = requireStudentId(userId);
        String sql = scheduleSelect() + """
                JOIN course_enrollments e ON e.section_id = cs.id
                WHERE e.student_id = ? AND e.status = 'ENROLLED' AND cs.term_id = ?
                ORDER BY s.day_of_week, s.start_period, s.start_week, c.course_code
                LIMIT 100
                """;
        return querySchedules(sql, studentId, termId);
    }

    public List<SectionRecord> teacherSections(long teacherUserId, long termId) throws SQLException {
        String sql = sectionSelect() + """
                WHERE cs.teacher_user_id = ? AND (? = 0 OR cs.term_id = ?)
                ORDER BY t.start_date DESC, c.course_code
                """;
        List<SectionRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, teacherUserId);
            statement.setLong(2, termId);
            statement.setLong(3, termId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(readSection(result));
                }
            }
        }
        return List.copyOf(rows);
    }

    public List<ScheduleRecord> teacherSchedule(long teacherUserId, long termId) throws SQLException {
        String sql = scheduleSelect() + """
                WHERE cs.teacher_user_id = ? AND cs.term_id = ?
                ORDER BY s.day_of_week, s.start_period, s.start_week, c.course_code
                LIMIT 100
                """;
        return querySchedules(sql, teacherUserId, termId);
    }

    public boolean isSectionTeacher(long sectionId, long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM course_sections WHERE id = ? AND teacher_user_id = ?")) {
            statement.setLong(1, sectionId);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public List<RosterRecord> roster(long sectionId) throws SQLException {
        String sql = """
                SELECT e.id AS enrollment_id, sp.id AS student_id, sp.student_number,
                       sp.full_name, e.status, g.score, g.grade_point, g.comment
                  FROM course_enrollments e
                  JOIN student_profiles sp ON sp.id = e.student_id
                  LEFT JOIN grades g ON g.enrollment_id = e.id
                 WHERE e.section_id = ? AND e.status = 'ENROLLED'
                 ORDER BY sp.student_number
                 LIMIT 100
                """;
        List<RosterRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new RosterRecord(
                            result.getLong("enrollment_id"), result.getLong("student_id"),
                            result.getString("student_number"), result.getString("full_name"),
                            EnrollmentStatus.valueOf(result.getString("status")),
                            result.getBigDecimal("score"), result.getBigDecimal("grade_point"),
                            result.getString("comment")));
                }
            }
        }
        return List.copyOf(rows);
    }

    public void saveGrade(
            long sectionId,
            long enrollmentId,
            BigDecimal score,
            String comment,
            String reason,
            long operatorUserId,
            boolean administrator) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                lockGradeSection(connection, sectionId, operatorUserId, administrator);
                verifyEnrollment(connection, sectionId, enrollmentId);
                BigDecimal gradePoint = GradePolicy.gradePoint(score);
                Long gradeId = null;
                BigDecimal oldScore = null;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT id, score FROM grades WHERE enrollment_id = ? FOR UPDATE")) {
                    find.setLong(1, enrollmentId);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            gradeId = result.getLong("id");
                            oldScore = result.getBigDecimal("score");
                        }
                    }
                }
                if (gradeId == null) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO grades
                                (enrollment_id, score, grade_point, comment, submitted_by_user_id)
                            VALUES (?, ?, ?, ?, ?)
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        insert.setLong(1, enrollmentId);
                        insert.setBigDecimal(2, score);
                        insert.setBigDecimal(3, gradePoint);
                        setNullableString(insert, 4, comment);
                        insert.setLong(5, operatorUserId);
                        insert.executeUpdate();
                        try (ResultSet keys = insert.getGeneratedKeys()) {
                            keys.next();
                            gradeId = keys.getLong(1);
                        }
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE grades
                               SET score = ?, grade_point = ?, comment = ?,
                                   submitted_by_user_id = ?, submitted_at = CURRENT_TIMESTAMP
                             WHERE id = ?
                            """)) {
                        update.setBigDecimal(1, score);
                        update.setBigDecimal(2, gradePoint);
                        setNullableString(update, 3, comment);
                        update.setLong(4, operatorUserId);
                        update.setLong(5, gradeId);
                        update.executeUpdate();
                    }
                }
                try (PreparedStatement history = connection.prepareStatement("""
                        INSERT INTO grade_change_history
                            (grade_id, old_score, new_score, changed_by_user_id, reason)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    history.setLong(1, gradeId);
                    if (oldScore == null) {
                        history.setNull(2, Types.DECIMAL);
                    } else {
                        history.setBigDecimal(2, oldScore);
                    }
                    history.setBigDecimal(3, score);
                    history.setLong(4, operatorUserId);
                    history.setString(5, reason);
                    history.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public void publishGrades(long sectionId, long operatorUserId, boolean administrator) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int enrolled;
                boolean alreadyPublished;
                long teacherUserId;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT teacher_user_id, enrolled_count, grades_published "
                                + "FROM course_sections WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, sectionId);
                    try (ResultSet result = lock.executeQuery()) {
                        if (!result.next()) {
                            throw new AcademicRuleException("教学班不存在");
                        }
                        teacherUserId = result.getLong("teacher_user_id");
                        enrolled = result.getInt("enrolled_count");
                        alreadyPublished = result.getBoolean("grades_published");
                    }
                }
                if (!administrator && teacherUserId != operatorUserId) {
                    throw new AcademicRuleException("只能发布本人教学班的成绩");
                }
                if (alreadyPublished) {
                    throw new AcademicRuleException("该教学班成绩已经发布");
                }
                int gradeCount;
                try (PreparedStatement count = connection.prepareStatement("""
                        SELECT COUNT(*)
                          FROM grades g
                          JOIN course_enrollments e ON e.id = g.enrollment_id
                         WHERE e.section_id = ? AND e.status = 'ENROLLED'
                        """)) {
                    count.setLong(1, sectionId);
                    try (ResultSet result = count.executeQuery()) {
                        result.next();
                        gradeCount = result.getInt(1);
                    }
                }
                if (gradeCount != enrolled) {
                    throw new AcademicRuleException("仍有学生未录入成绩，不能发布");
                }
                try (PreparedStatement grades = connection.prepareStatement("""
                        UPDATE grades
                           SET published_at = CURRENT_TIMESTAMP
                         WHERE enrollment_id IN (
                               SELECT id FROM course_enrollments
                                WHERE section_id = ? AND status = 'ENROLLED')
                        """)) {
                    grades.setLong(1, sectionId);
                    grades.executeUpdate();
                }
                try (PreparedStatement section = connection.prepareStatement("""
                        UPDATE course_sections
                           SET grades_published = TRUE, status = 'COMPLETED'
                         WHERE id = ?
                        """)) {
                    section.setLong(1, sectionId);
                    section.executeUpdate();
                }
                List<NotificationDraft> drafts = gradeNotificationDrafts(
                        connection, sectionId, operatorUserId);
                notifications.insertBatch(connection, drafts);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public List<GradeRecord> myGrades(long userId) throws SQLException {
        long studentId = requireStudentId(userId);
        String sql = """
                SELECT t.term_name, c.course_code, c.course_name, c.credits,
                       u.display_name AS teacher_name, g.score, g.grade_point
                  FROM grades g
                  JOIN course_enrollments e ON e.id = g.enrollment_id
                  JOIN course_sections cs ON cs.id = e.section_id
                  JOIN academic_terms t ON t.id = cs.term_id
                  JOIN courses c ON c.id = cs.course_id
                  JOIN users u ON u.id = cs.teacher_user_id
                 WHERE e.student_id = ? AND e.status = 'ENROLLED'
                   AND cs.grades_published = TRUE AND g.published_at IS NOT NULL
                 ORDER BY t.start_date DESC, c.course_code
                """;
        List<GradeRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new GradeRecord(
                            result.getString("term_name"), result.getString("course_code"),
                            result.getString("course_name"), result.getBigDecimal("credits"),
                            result.getString("teacher_name"), result.getBigDecimal("score"),
                            result.getBigDecimal("grade_point")));
                }
            }
        }
        return List.copyOf(rows);
    }

    private String sectionSelect() {
        return sectionSelect(false);
    }

    private String sectionSelect(boolean includeOwnEnrollment) {
        return """
                SELECT cs.id, cs.term_id, t.term_name, cs.course_id, c.course_code,
                       c.course_name, c.credits, cs.section_code, cs.teacher_user_id,
                       u.display_name AS teacher_name, cs.capacity, cs.enrolled_count,
                       cs.status, cs.grades_published,
                       COALESCE((
                           SELECT GROUP_CONCAT(
                               CONCAT(
                                   CASE s.day_of_week
                                       WHEN 1 THEN '周一' WHEN 2 THEN '周二' WHEN 3 THEN '周三'
                                       WHEN 4 THEN '周四' WHEN 5 THEN '周五' WHEN 6 THEN '周六'
                                       ELSE '周日'
                                   END,
                                   ' 第', s.start_period, '-', s.end_period, '节 ',
                                   s.start_week, '-', s.end_week, '周')
                               ORDER BY s.day_of_week, s.start_period, s.start_week
                               SEPARATOR '；')
                             FROM class_schedules s
                            WHERE s.section_id = cs.id
                       ), '') AS schedule_summary,
                       COALESCE((
                           SELECT GROUP_CONCAT(DISTINCT s.classroom ORDER BY s.classroom SEPARATOR '、')
                             FROM class_schedules s
                            WHERE s.section_id = cs.id
                       ), '') AS classroom_summary
                """ + (includeOwnEnrollment
                ? ", own.id AS own_enrollment_id, own.status AS own_enrollment_status\n"
                : "\n") + """
                  FROM course_sections cs
                  JOIN academic_terms t ON t.id = cs.term_id
                  JOIN courses c ON c.id = cs.course_id
                  JOIN users u ON u.id = cs.teacher_user_id
                """ + (includeOwnEnrollment
                ? " LEFT JOIN course_enrollments own ON own.section_id = cs.id AND own.student_id = ?\n"
                : "");
    }

    private SectionRecord readSection(ResultSet result) throws SQLException {
        return new SectionRecord(
                result.getLong("id"), result.getLong("term_id"), result.getString("term_name"),
                result.getLong("course_id"), result.getString("course_code"),
                result.getString("course_name"), result.getBigDecimal("credits"),
                result.getString("section_code"), result.getLong("teacher_user_id"),
                result.getString("teacher_name"), result.getInt("capacity"),
                result.getInt("enrolled_count"), CourseSectionStatus.valueOf(result.getString("status")),
                result.getBoolean("grades_published"), result.getString("schedule_summary"),
                result.getString("classroom_summary"),
                null, null);
    }

    private String scheduleSelect() {
        return """
                SELECT cs.id AS section_id, cs.term_id, t.term_name,
                       c.course_code, c.course_name, cs.section_code,
                       u.display_name AS teacher_name,
                       s.day_of_week, s.start_period, s.end_period,
                       s.start_week, s.end_week, s.classroom
                  FROM class_schedules s
                  JOIN course_sections cs ON cs.id = s.section_id
                  JOIN academic_terms t ON t.id = cs.term_id
                  JOIN courses c ON c.id = cs.course_id
                  JOIN users u ON u.id = cs.teacher_user_id
                """;
    }

    private List<ScheduleRecord> querySchedules(String sql, long first, long second) throws SQLException {
        List<ScheduleRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new ScheduleRecord(
                            result.getLong("section_id"), result.getLong("term_id"),
                            result.getString("term_name"), result.getString("course_code"),
                            result.getString("course_name"), result.getString("section_code"),
                            result.getString("teacher_name"), result.getInt("day_of_week"),
                            result.getInt("start_period"), result.getInt("end_period"),
                            result.getInt("start_week"), result.getInt("end_week"),
                            result.getString("classroom")));
                }
            }
        }
        return List.copyOf(rows);
    }

    private long requireStudentId(long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM student_profiles WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("当前账号没有关联学生档案");
                }
                return result.getLong("id");
            }
        }
    }

    private EnrollmentContext lockEnrollmentContext(Connection connection, long sectionId) throws SQLException {
        String sql = """
                SELECT cs.term_id, cs.course_id, cs.capacity, cs.enrolled_count,
                       cs.grades_published,
                       cs.status AS section_status, t.status AS term_status,
                       t.selection_start, t.selection_end
                  FROM course_sections cs
                  JOIN academic_terms t ON t.id = cs.term_id
                 WHERE cs.id = ?
                 FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("教学班不存在");
                }
                return new EnrollmentContext(
                        result.getLong("term_id"), result.getLong("course_id"),
                        result.getInt("capacity"), result.getInt("enrolled_count"),
                        result.getBoolean("grades_published"),
                        CourseSectionStatus.valueOf(result.getString("section_status")),
                        AcademicTermStatus.valueOf(result.getString("term_status")),
                        result.getTimestamp("selection_start").toInstant(),
                        result.getTimestamp("selection_end").toInstant());
            }
        }
    }

    private void validateSelectionWindow(EnrollmentContext context) {
        Instant now = Instant.now();
        if (context.gradesPublished()
                || context.sectionStatus() != CourseSectionStatus.OPEN
                || context.termStatus() != AcademicTermStatus.SELECTION
                || now.isBefore(context.selectionStart()) || now.isAfter(context.selectionEnd())) {
            throw new AcademicRuleException("当前不在该教学班的选课开放时间内");
        }
    }

    private boolean hasSameCourse(Connection connection, long studentId, long termId, long courseId)
            throws SQLException {
        String sql = """
                SELECT 1
                  FROM course_enrollments e
                  JOIN course_sections cs ON cs.id = e.section_id
                 WHERE e.student_id = ? AND e.status = 'ENROLLED'
                   AND cs.term_id = ? AND cs.course_id = ?
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            statement.setLong(2, termId);
            statement.setLong(3, courseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasScheduleConflict(Connection connection, long studentId, long targetSectionId)
            throws SQLException {
        String sql = """
                SELECT 1
                  FROM course_enrollments e
                  JOIN class_schedules existing_schedule ON existing_schedule.section_id = e.section_id
                  JOIN class_schedules target_schedule ON target_schedule.section_id = ?
                 WHERE e.student_id = ? AND e.status = 'ENROLLED'
                   AND existing_schedule.day_of_week = target_schedule.day_of_week
                   AND existing_schedule.start_period <= target_schedule.end_period
                   AND existing_schedule.end_period >= target_schedule.start_period
                   AND existing_schedule.start_week <= target_schedule.end_week
                   AND existing_schedule.end_week >= target_schedule.start_week
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, targetSectionId);
            statement.setLong(2, studentId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void updateEnrollmentCount(Connection connection, long sectionId, int delta) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE course_sections
                   SET enrolled_count = enrolled_count + ?
                 WHERE id = ?
                """)) {
            statement.setInt(1, delta);
            statement.setLong(2, sectionId);
            statement.executeUpdate();
        }
    }

    private void validateSectionReferences(Connection connection, CreateSection command) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM academic_terms WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, command.termId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("学期不存在");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM courses WHERE id = ? AND enabled = TRUE")) {
            statement.setLong(1, command.courseId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("课程不存在或已停用");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                  FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN roles r ON r.id = ur.role_id
                 WHERE u.id = ? AND u.enabled = TRUE
                   AND r.role_code IN ('TEACHER', 'SUPER_ADMIN')
                 LIMIT 1
                """)) {
            statement.setLong(1, command.teacherUserId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("授课教师账号不存在或没有教师权限");
                }
            }
        }
    }

    private String courseName(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT course_name FROM courses WHERE id = ?")) {
            statement.setLong(1, courseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("课程不存在或已停用");
                }
                return result.getString("course_name");
            }
        }
    }

    private List<NotificationDraft> gradeNotificationDrafts(
            Connection connection,
            long sectionId,
            long operatorUserId) throws SQLException {
        String sql = """
                SELECT sp.user_id, c.course_name, teacher.display_name AS teacher_name
                  FROM course_sections cs
                  JOIN courses c ON c.id = cs.course_id
                  JOIN users teacher ON teacher.id = cs.teacher_user_id
                  JOIN course_enrollments e ON e.section_id = cs.id
                  JOIN student_profiles sp ON sp.id = e.student_id
                 WHERE cs.id = ? AND e.status = 'ENROLLED'
                 ORDER BY e.id
                """;
        List<NotificationDraft> drafts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String courseName = result.getString("course_name");
                    String teacherName = result.getString("teacher_name");
                    drafts.add(new NotificationDraft(
                            result.getLong("user_id"), operatorUserId,
                            NotificationType.GRADE_PUBLISHED, NotificationSource.ACADEMIC,
                            "成绩发布通知",
                            "任课教师" + teacherName + "所授《" + courseName
                                    + "》的最终成绩已发布，请查看。",
                            NotificationTarget.STUDENT_GRADES, sectionId));
                }
            }
        }
        return List.copyOf(drafts);
    }

    private void validateScheduleAvailability(Connection connection, CreateSection command) throws SQLException {
        for (ScheduleSlot slot : command.schedules()) {
            if (hasTeacherScheduleConflict(connection, command.termId(), command.teacherUserId(), slot)) {
                throw new AcademicRuleException("授课教师在所选周次和节次已有课程，请重新选择时间");
            }
            if (hasClassroomScheduleConflict(connection, command.termId(), slot)) {
                throw new AcademicRuleException("教室“" + slot.classroom() + "”在所选周次和节次已被占用");
            }
        }
    }

    private boolean hasTeacherScheduleConflict(
            Connection connection, long termId, long teacherUserId, ScheduleSlot slot) throws SQLException {
        String sql = """
                SELECT 1
                  FROM course_sections cs
                  JOIN class_schedules s ON s.section_id = cs.id
                 WHERE cs.term_id = ? AND cs.teacher_user_id = ?
                   AND s.day_of_week = ?
                   AND s.start_period <= ? AND s.end_period >= ?
                   AND s.start_week <= ? AND s.end_week >= ?
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, termId);
            statement.setLong(2, teacherUserId);
            bindScheduleOverlap(statement, 3, slot);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasClassroomScheduleConflict(
            Connection connection, long termId, ScheduleSlot slot) throws SQLException {
        String sql = """
                SELECT 1
                  FROM course_sections cs
                  JOIN class_schedules s ON s.section_id = cs.id
                 WHERE cs.term_id = ? AND LOWER(TRIM(s.classroom)) = LOWER(TRIM(?))
                   AND s.day_of_week = ?
                   AND s.start_period <= ? AND s.end_period >= ?
                   AND s.start_week <= ? AND s.end_week >= ?
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, termId);
            statement.setString(2, slot.classroom());
            bindScheduleOverlap(statement, 3, slot);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void bindScheduleOverlap(PreparedStatement statement, int firstIndex, ScheduleSlot slot)
            throws SQLException {
        statement.setInt(firstIndex, slot.dayOfWeek());
        statement.setInt(firstIndex + 1, slot.endPeriod());
        statement.setInt(firstIndex + 2, slot.startPeriod());
        statement.setInt(firstIndex + 3, slot.endWeek());
        statement.setInt(firstIndex + 4, slot.startWeek());
    }

    private void lockGradeSection(
            Connection connection, long sectionId, long userId, boolean administrator) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT teacher_user_id, grades_published
                  FROM course_sections
                 WHERE id = ?
                 FOR UPDATE
                """)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("教学班不存在");
                }
                if (!administrator && result.getLong("teacher_user_id") != userId) {
                    throw new AcademicRuleException("只能录入本人教学班的成绩");
                }
                if (result.getBoolean("grades_published")) {
                    throw new AcademicRuleException("成绩已发布，不能直接修改");
                }
            }
        }
    }

    private void verifyEnrollment(Connection connection, long sectionId, long enrollmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM course_enrollments
                 WHERE id = ? AND section_id = ? AND status = 'ENROLLED'
                """)) {
            statement.setLong(1, enrollmentId);
            statement.setLong(2, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AcademicRuleException("学生不在该教学班有效名单中");
                }
            }
        }
    }

    private void bindCourseSearch(PreparedStatement statement, String keyword, String pattern) throws SQLException {
        statement.setString(1, keyword);
        statement.setString(2, pattern);
        statement.setString(3, pattern);
    }

    private void bindSectionSearch(
            PreparedStatement statement, long termId, String keyword, String pattern) throws SQLException {
        statement.setLong(1, termId);
        statement.setLong(2, termId);
        statement.setString(3, keyword);
        statement.setString(4, pattern);
        statement.setString(5, pattern);
        statement.setString(6, pattern);
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    public static final class AcademicRuleException extends RuntimeException {
        public AcademicRuleException(String message) {
            super(message);
        }
    }

    public record AcademicReferences(
            List<TermReference> terms,
            List<CourseReference> courses,
            List<TeacherReference> teachers) {
    }

    public record TermReference(long id, String name, AcademicTermStatus status) {
    }

    public record CourseReference(long id, String code, String name, BigDecimal credits) {
    }

    public record TeacherReference(long userId, String username, String displayName) {
    }

    public record CourseRecord(
            long id, String code, String name, BigDecimal credits,
            int totalHours, String description, boolean enabled) {
    }

    public record CoursePage(List<CourseRecord> rows, int page, int pageSize, int total) {
    }

    public record CreateCourse(
            String courseCode, String courseName, BigDecimal credits,
            int totalHours, String description) {
    }

    public record UpdateCourse(
            long courseId,
            String courseName,
            BigDecimal credits,
            int totalHours,
            String description,
            boolean enabled) {
    }

    public record SectionRecord(
            long id,
            long termId,
            String termName,
            long courseId,
            String courseCode,
            String courseName,
            BigDecimal credits,
            String sectionCode,
            long teacherUserId,
            String teacherName,
            int capacity,
            int enrolledCount,
            CourseSectionStatus status,
            boolean gradesPublished,
            String scheduleSummary,
            String classroomSummary,
            Long ownEnrollmentId,
            String ownEnrollmentStatus) {

        public SectionRecord withEnrollment(Long enrollmentId, String enrollmentStatus) {
            return new SectionRecord(
                    id, termId, termName, courseId, courseCode, courseName, credits,
                    sectionCode, teacherUserId, teacherName, capacity, enrolledCount,
                    status, gradesPublished, scheduleSummary, classroomSummary,
                    enrollmentId, enrollmentStatus);
        }
    }

    public record SectionPage(List<SectionRecord> rows, int page, int pageSize, int total) {
    }

    public record CreateSection(
            long termId,
            long courseId,
            String sectionCode,
            long teacherUserId,
            int capacity,
            CourseSectionStatus status,
            List<ScheduleSlot> schedules) {
    }

    public record ScheduleRecord(
            long sectionId,
            long termId,
            String termName,
            String courseCode,
            String courseName,
            String sectionCode,
            String teacherName,
            int dayOfWeek,
            int startPeriod,
            int endPeriod,
            int startWeek,
            int endWeek,
            String classroom) {
    }

    private record EnrollmentContext(
            long termId,
            long courseId,
            int capacity,
            int enrolledCount,
            boolean gradesPublished,
            CourseSectionStatus sectionStatus,
            AcademicTermStatus termStatus,
            Instant selectionStart,
            Instant selectionEnd) {
    }

    public record RosterRecord(
            long enrollmentId,
            long studentId,
            String studentNumber,
            String fullName,
            EnrollmentStatus status,
            BigDecimal score,
            BigDecimal gradePoint,
            String comment) {
    }

    public record GradeRecord(
            String termName,
            String courseCode,
            String courseName,
            BigDecimal credits,
            String teacherName,
            BigDecimal score,
            BigDecimal gradePoint) {
    }
}
