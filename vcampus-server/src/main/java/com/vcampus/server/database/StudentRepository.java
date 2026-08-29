package com.vcampus.server.database;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.StudentStatus;
import com.vcampus.common.model.StudentStatusPolicy;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.server.model.StudentProfile;
import com.vcampus.server.model.StudentStatusRecord;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StudentRepository {
    private static final String PROFILE_COLUMNS = """
            sp.id, sp.user_id, sp.student_number, sp.full_name, sp.gender, sp.birth_date,
            sp.department_id, d.department_name, sp.major_id, m.major_name,
            sp.class_id, c.class_name, sp.enrollment_year, sp.status,
            sp.phone, sp.email, sp.address
            """;
    private static final String PROFILE_JOINS = """
            FROM student_profiles sp
            JOIN departments d ON d.id = sp.department_id
            JOIN majors m ON m.id = sp.major_id
            JOIN administrative_classes c ON c.id = sp.class_id
            """;

    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;

    public StudentRepository(
            ConnectionFactory connectionFactory,
            NotificationWriter notifications) {
        this.connectionFactory = connectionFactory;
        this.notifications = notifications;
    }

    public Optional<StudentProfile> findByUserId(long userId) throws SQLException {
        String sql = "SELECT " + PROFILE_COLUMNS + PROFILE_JOINS + " WHERE sp.user_id = ?";
        return findOne(sql, userId);
    }

    public Optional<StudentProfile> findById(long studentId) throws SQLException {
        String sql = "SELECT " + PROFILE_COLUMNS + PROFILE_JOINS + " WHERE sp.id = ?";
        return findOne(sql, studentId);
    }

    public StudentPage search(String keyword, StudentStatus status, int page, int pageSize) throws SQLException {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String where = " WHERE (? = '' OR sp.student_number LIKE ? OR sp.full_name LIKE ?)"
                + " AND (? IS NULL OR sp.status = ?)";
        String pattern = "%" + normalizedKeyword + "%";
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            String countSql = "SELECT COUNT(*) " + PROFILE_JOINS + where;
            try (PreparedStatement count = connection.prepareStatement(countSql)) {
                bindSearch(count, normalizedKeyword, pattern, status);
                try (ResultSet result = count.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }

            String dataSql = "SELECT " + PROFILE_COLUMNS + PROFILE_JOINS + where
                    + " ORDER BY sp.student_number LIMIT ? OFFSET ?";
            List<StudentProfile> rows = new ArrayList<>();
            try (PreparedStatement data = connection.prepareStatement(dataSql)) {
                int next = bindSearch(data, normalizedKeyword, pattern, status);
                data.setInt(next++, pageSize);
                data.setInt(next, (page - 1) * pageSize);
                try (ResultSet result = data.executeQuery()) {
                    while (result.next()) {
                        rows.add(readProfile(result));
                    }
                }
            }
            return new StudentPage(List.copyOf(rows), page, pageSize, total);
        }
    }

    public boolean updateContact(long studentId, String phone, String email, String address) throws SQLException {
        String sql = "UPDATE student_profiles SET phone = ?, email = ?, address = ? WHERE id = ?";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullableString(statement, 1, phone);
            setNullableString(statement, 2, email);
            setNullableString(statement, 3, address);
            statement.setLong(4, studentId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean updateStudent(UpdateStudent command) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                validateAcademicReferences(
                        connection, command.departmentId(), command.majorId(),
                        command.classId(), command.enrollmentYear());
                String profileSql = """
                        UPDATE student_profiles
                           SET full_name = ?, gender = ?, birth_date = ?,
                               department_id = ?, major_id = ?, class_id = ?, enrollment_year = ?,
                               phone = ?, email = ?, address = ?
                         WHERE id = ?
                        """;
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(profileSql)) {
                    statement.setString(1, command.fullName());
                    statement.setString(2, command.gender().name());
                    if (command.birthDate() == null) {
                        statement.setNull(3, Types.DATE);
                    } else {
                        statement.setDate(3, Date.valueOf(command.birthDate()));
                    }
                    statement.setLong(4, command.departmentId());
                    statement.setLong(5, command.majorId());
                    statement.setLong(6, command.classId());
                    statement.setInt(7, command.enrollmentYear());
                    setNullableString(statement, 8, command.phone());
                    setNullableString(statement, 9, command.email());
                    setNullableString(statement, 10, command.address());
                    statement.setLong(11, command.studentId());
                    updated = statement.executeUpdate();
                }
                if (updated == 1) {
                    String userSql = """
                            UPDATE users u
                            JOIN student_profiles sp ON sp.user_id = u.id
                               SET u.display_name = ?
                             WHERE sp.id = ?
                            """;
                    try (PreparedStatement statement = connection.prepareStatement(userSql)) {
                        statement.setString(1, command.fullName());
                        statement.setLong(2, command.studentId());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return updated == 1;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public StudentStatus changeStatus(
            long studentId,
            StudentStatus newStatus,
            String reason,
            long operatorUserId,
            String operatorDisplayName) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                StudentStatus oldStatus;
                long studentUserId;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT user_id, status FROM student_profiles WHERE id = ? FOR UPDATE")) {
                    lock.setLong(1, studentId);
                    try (ResultSet result = lock.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Student profile not found: " + studentId);
                        }
                        studentUserId = result.getLong("user_id");
                        oldStatus = StudentStatus.valueOf(result.getString("status"));
                    }
                }
                if (!StudentStatusPolicy.canTransition(oldStatus, newStatus)) {
                    throw new SQLException("Invalid student status transition: " + oldStatus + " -> " + newStatus);
                }
                if (oldStatus != newStatus) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE student_profiles SET status = ? WHERE id = ?")) {
                        update.setString(1, newStatus.name());
                        update.setLong(2, studentId);
                        update.executeUpdate();
                    }
                    insertStatusHistory(
                            connection, studentId, oldStatus, newStatus, reason, operatorUserId);
                    notifications.insert(connection, new NotificationDraft(
                            studentUserId, operatorUserId,
                            NotificationType.STUDENT_STATUS_CHANGED,
                            NotificationSource.STUDENT_STATUS,
                            "学籍状态变更",
                            operatorDisplayName + "已将您的学籍状态由“"
                                    + oldStatus.displayName() + "”变更为“"
                                    + newStatus.displayName() + "”。原因：" + reason + "。",
                            NotificationTarget.STUDENT_PROFILE, studentId));
                }
                connection.commit();
                return oldStatus;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public List<StudentStatusRecord> statusHistory(long studentId) throws SQLException {
        String sql = """
                SELECT h.id, h.old_status, h.new_status, h.reason,
                       u.display_name AS operator_name, h.changed_at
                  FROM student_status_history h
                  JOIN users u ON u.id = h.changed_by_user_id
                 WHERE h.student_id = ?
                 ORDER BY h.changed_at DESC, h.id DESC
                 LIMIT 20
                """;
        List<StudentStatusRecord> rows = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String oldValue = result.getString("old_status");
                    Timestamp changedAt = result.getTimestamp("changed_at");
                    rows.add(new StudentStatusRecord(
                            result.getLong("id"),
                            oldValue == null ? null : StudentStatus.valueOf(oldValue),
                            StudentStatus.valueOf(result.getString("new_status")),
                            result.getString("reason"),
                            result.getString("operator_name"),
                            changedAt.toInstant()));
                }
            }
        }
        return List.copyOf(rows);
    }

    public AcademicReferences academicReferences() throws SQLException {
        List<ReferenceItem> departments = new ArrayList<>();
        List<ReferenceItem> majors = new ArrayList<>();
        List<ReferenceItem> classes = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, department_code, department_name FROM departments WHERE enabled = TRUE ORDER BY department_code");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    departments.add(new ReferenceItem(
                            result.getLong("id"), 0, result.getString("department_code"),
                            result.getString("department_name"), 0));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, department_id, major_code, major_name FROM majors WHERE enabled = TRUE ORDER BY major_code");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    majors.add(new ReferenceItem(
                            result.getLong("id"), result.getLong("department_id"),
                            result.getString("major_code"), result.getString("major_name"), 0));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, major_id, class_code, class_name, enrollment_year FROM administrative_classes WHERE enabled = TRUE ORDER BY class_code");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    classes.add(new ReferenceItem(
                            result.getLong("id"), result.getLong("major_id"),
                            result.getString("class_code"), result.getString("class_name"),
                            result.getInt("enrollment_year")));
                }
            }
        }
        return new AcademicReferences(List.copyOf(departments), List.copyOf(majors), List.copyOf(classes));
    }

    private Optional<StudentProfile> findOne(String sql, long id) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProfile(result)) : Optional.empty();
            }
        }
    }

    private StudentProfile readProfile(ResultSet result) throws SQLException {
        Date birthDate = result.getDate("birth_date");
        return new StudentProfile(
                result.getLong("id"),
                result.getLong("user_id"),
                result.getString("student_number"),
                result.getString("full_name"),
                Gender.valueOf(result.getString("gender")),
                birthDate == null ? null : birthDate.toLocalDate(),
                result.getLong("department_id"),
                result.getString("department_name"),
                result.getLong("major_id"),
                result.getString("major_name"),
                result.getLong("class_id"),
                result.getString("class_name"),
                result.getInt("enrollment_year"),
                StudentStatus.valueOf(result.getString("status")),
                result.getString("phone"),
                result.getString("email"),
                result.getString("address"));
    }

    private int bindSearch(
            PreparedStatement statement,
            String keyword,
            String pattern,
            StudentStatus status) throws SQLException {
        statement.setString(1, keyword);
        statement.setString(2, pattern);
        statement.setString(3, pattern);
        if (status == null) {
            statement.setNull(4, Types.VARCHAR);
            statement.setNull(5, Types.VARCHAR);
        } else {
            statement.setString(4, status.name());
            statement.setString(5, status.name());
        }
        return 6;
    }

    private void validateAcademicReferences(
            Connection connection,
            long departmentId,
            long majorId,
            long classId,
            int enrollmentYear) throws SQLException {
        String sql = """
                SELECT 1
                  FROM departments d
                  JOIN majors m ON m.department_id = d.id
                  JOIN administrative_classes c ON c.major_id = m.id
                 WHERE d.id = ? AND m.id = ? AND c.id = ?
                   AND c.enrollment_year = ?
                   AND d.enabled = TRUE AND m.enabled = TRUE AND c.enabled = TRUE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, departmentId);
            statement.setLong(2, majorId);
            statement.setLong(3, classId);
            statement.setInt(4, enrollmentYear);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("学院、专业、班级或入学年份不匹配");
                }
            }
        }
    }

    private void insertStatusHistory(
            Connection connection,
            long studentId,
            StudentStatus oldStatus,
            StudentStatus newStatus,
            String reason,
            long operatorUserId) throws SQLException {
        String sql = """
                INSERT INTO student_status_history
                    (student_id, old_status, new_status, reason, changed_by_user_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            if (oldStatus == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, oldStatus.name());
            }
            statement.setString(3, newStatus.name());
            statement.setString(4, reason);
            statement.setLong(5, operatorUserId);
            statement.executeUpdate();
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    public record StudentPage(List<StudentProfile> rows, int page, int pageSize, int total) {
    }

    public record UpdateStudent(
            long studentId,
            String fullName,
            Gender gender,
            LocalDate birthDate,
            long departmentId,
            long majorId,
            long classId,
            int enrollmentYear,
            String phone,
            String email,
            String address) {
    }

    public record AcademicReferences(
            List<ReferenceItem> departments,
            List<ReferenceItem> majors,
            List<ReferenceItem> classes) {
    }

    public record ReferenceItem(long id, long parentId, String code, String name, int year) {
    }
}
