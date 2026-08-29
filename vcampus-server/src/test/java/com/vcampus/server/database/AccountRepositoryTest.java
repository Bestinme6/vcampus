package com.vcampus.server.database;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.UserRole;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AccountStore.AccountPage;
import com.vcampus.server.database.AccountStore.CreateStudentAccount;
import com.vcampus.server.database.AccountStore.CreateTeacherAccount;
import com.vcampus.server.database.AccountStore.MutationResult;
import com.vcampus.server.security.PasswordHasher.PasswordHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRepositoryTest {
    private ConnectionFactory connections;
    private AccountRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        connections = new ConnectionFactory(config);
        createSchema();
        seedReferences();
        repository = new AccountRepository(connections, new NotificationRepository(connections));
    }

    @Test
    void createsStudentUserRolesProfileAndInitialHistoryInOneTransaction() throws SQLException {
        long profileId = repository.createStudent(studentCommand(100L), password(), 900L);

        assertTrue(profileId > 0);
        assertEquals(1, count("users", "username = '2026000002'"));
        assertEquals(3, count("user_roles", "user_id = (SELECT id FROM users WHERE username = '2026000002')"));
        assertEquals(1, count("student_profiles", "student_number = '2026000002'"));
        assertEquals(1, count("student_status_history", "student_id = " + profileId));
        assertEquals("ENROLLED", scalarString(
                "SELECT new_status FROM student_status_history WHERE student_id = " + profileId));
    }

    @Test
    void rollsBackStudentCreationWhenClassDoesNotMatchMajor() {
        assertThrows(SQLException.class,
                () -> repository.createStudent(studentCommand(999L), password(), 900L));

        assertEquals(0, countUnchecked("users", "username = '2026000002'"));
        assertEquals(0, countUnchecked("student_profiles", "student_number = '2026000002'"));
    }

    @Test
    void createsTeacherAndRollsBackWhenDepartmentIsMissing() throws SQLException {
        long profileId = repository.createTeacher(teacherCommand(10L, "T0000002"), password());

        assertTrue(profileId > 0);
        assertEquals(1, count("teacher_profiles", "teacher_number = 'T0000002'"));
        assertEquals(3, count("user_roles", "user_id = (SELECT id FROM users WHERE username = 'T0000002')"));

        assertThrows(SQLException.class,
                () -> repository.createTeacher(teacherCommand(999L, "T0000003"), password()));
        assertEquals(0, count("users", "username = 'T0000003'"));
    }

    @Test
    void searchExcludesSuperAdministrators() throws SQLException {
        repository.createStudent(studentCommand(100L), password(), 900L);
        insertSuperAdministrator();

        AccountPage page = repository.search("", null, null, 1, 8);

        assertEquals(1, page.total());
        assertEquals("2026000002", page.rows().getFirst().username());
        assertEquals(UserRole.STUDENT, page.rows().getFirst().baseIdentity());
        assertEquals(Set.of(UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN),
                page.rows().getFirst().administrativeRoles());
    }

    @Test
    void replacesAdministrativeRolesAndUpdatesStateAndPassword() throws SQLException {
        repository.createTeacher(teacherCommand(10L, "T0000002"), password());
        long userId = scalarLong("SELECT id FROM users WHERE username = 'T0000002'");

        assertEquals(MutationResult.CHANGED, repository.replaceAdministrativeRoles(
                userId, UserRole.TEACHER, Set.of(UserRole.BANK_ADMIN),
                900L, "系统管理员"));
        assertEquals(Set.of("TEACHER", "BANK_ADMIN"), roleCodes(userId));
        assertEquals("ROLES_CHANGED", lastNotificationType(userId));

        assertEquals(MutationResult.UNCHANGED, repository.replaceAdministrativeRoles(
                userId, UserRole.TEACHER, Set.of(UserRole.BANK_ADMIN),
                900L, "系统管理员"));
        assertEquals(1, notificationCount(userId));

        assertEquals(MutationResult.CHANGED,
                repository.setEnabled(userId, false, 900L, "系统管理员"));
        assertFalse(scalarBoolean("SELECT enabled FROM users WHERE id = " + userId));
        assertEquals("ACCOUNT_DISABLED", lastNotificationType(userId));
        assertEquals(MutationResult.UNCHANGED,
                repository.setEnabled(userId, false, 900L, "系统管理员"));

        assertEquals(MutationResult.CHANGED, repository.resetPassword(
                userId, new PasswordHash("new-hash", "new-salt"),
                900L, "系统管理员"));
        assertEquals("new-hash", scalarString("SELECT password_hash FROM users WHERE id = " + userId));
        assertTrue(scalarBoolean("SELECT force_password_change FROM users WHERE id = " + userId));
        assertEquals("PASSWORD_RESET", lastNotificationType(userId));
        String content = scalarString(
                "SELECT content FROM notifications WHERE recipient_user_id = " + userId
                        + " ORDER BY id DESC LIMIT 1");
        assertFalse(content.contains("new-hash"));
        assertFalse(content.contains("new-salt"));
    }

    @Test
    void notificationFailureRollsBackAccountSecurityMutations() throws SQLException {
        repository.createTeacher(teacherCommand(10L, "T0000002"), password());
        long userId = scalarLong("SELECT id FROM users WHERE username = 'T0000002'");
        AccountRepository failing = new AccountRepository(
                connections, new FailingNotificationWriter());

        assertThrows(SQLException.class, () -> failing.replaceAdministrativeRoles(
                userId, UserRole.TEACHER, Set.of(UserRole.BANK_ADMIN),
                900L, "系统管理员"));
        assertEquals(Set.of("TEACHER", "ACADEMIC_ADMIN", "FORUM_ADMIN"), roleCodes(userId));

        assertThrows(SQLException.class,
                () -> failing.setEnabled(userId, false, 900L, "系统管理员"));
        assertTrue(scalarBoolean("SELECT enabled FROM users WHERE id = " + userId));

        assertThrows(SQLException.class, () -> failing.resetPassword(
                userId, new PasswordHash("new-hash", "new-salt"),
                900L, "系统管理员"));
        assertEquals("hash", scalarString("SELECT password_hash FROM users WHERE id = " + userId));
    }

    private CreateStudentAccount studentCommand(long classId) {
        return new CreateStudentAccount(
                "2026000002", "王同学", Gender.FEMALE, LocalDate.of(2007, 2, 3),
                10L, 20L, classId, 2026,
                "13800000002", "student2@vcampus.edu", "东南路2号",
                Set.of(UserRole.STUDENT, UserRole.LIBRARY_ADMIN, UserRole.FORUM_ADMIN));
    }

    private CreateTeacherAccount teacherCommand(long departmentId, String teacherNumber) {
        return new CreateTeacherAccount(
                teacherNumber, "王老师", departmentId, "副教授",
                "13900000002", "teacher2@vcampus.edu",
                Set.of(UserRole.TEACHER, UserRole.ACADEMIC_ADMIN, UserRole.FORUM_ADMIN));
    }

    private PasswordHash password() {
        return new PasswordHash("hash", "salt");
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) UNIQUE NOT NULL, password_hash VARCHAR(255) NOT NULL, password_salt VARCHAR(128) NOT NULL, display_name VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL, force_password_change BOOLEAN NOT NULL, last_login_at TIMESTAMP NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, recipient_user_id BIGINT NOT NULL, sender_user_id BIGINT, notification_type VARCHAR(40) NOT NULL, source_module VARCHAR(40) NOT NULL, title VARCHAR(160) NOT NULL, content VARCHAR(1000) NOT NULL, target VARCHAR(40) NOT NULL, related_entity_id BIGINT, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (recipient_user_id) REFERENCES users(id), FOREIGN KEY (sender_user_id) REFERENCES users(id))");
            statement.execute("CREATE TABLE roles (id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64) UNIQUE NOT NULL, role_name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE user_roles (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY (user_id, role_id), FOREIGN KEY (user_id) REFERENCES users(id), FOREIGN KEY (role_id) REFERENCES roles(id))");
            statement.execute("CREATE TABLE departments (id BIGINT PRIMARY KEY, department_code VARCHAR(32), department_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, department_id BIGINT NOT NULL, major_code VARCHAR(32), major_name VARCHAR(100), enabled BOOLEAN, FOREIGN KEY (department_id) REFERENCES departments(id))");
            statement.execute("CREATE TABLE administrative_classes (id BIGINT PRIMARY KEY, major_id BIGINT NOT NULL, class_code VARCHAR(32), class_name VARCHAR(100), enrollment_year INT, enabled BOOLEAN, FOREIGN KEY (major_id) REFERENCES majors(id))");
            statement.execute("CREATE TABLE student_profiles (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL, student_number VARCHAR(10) UNIQUE NOT NULL, full_name VARCHAR(100) NOT NULL, gender VARCHAR(16), birth_date DATE, department_id BIGINT NOT NULL, major_id BIGINT NOT NULL, class_id BIGINT NOT NULL, enrollment_year INT NOT NULL, status VARCHAR(16), phone VARCHAR(32), email VARCHAR(128), address VARCHAR(255), FOREIGN KEY (user_id) REFERENCES users(id), FOREIGN KEY (department_id) REFERENCES departments(id), FOREIGN KEY (major_id) REFERENCES majors(id), FOREIGN KEY (class_id) REFERENCES administrative_classes(id))");
            statement.execute("CREATE TABLE teacher_profiles (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL, teacher_number VARCHAR(32) UNIQUE NOT NULL, full_name VARCHAR(100) NOT NULL, department_id BIGINT NOT NULL, professional_title VARCHAR(100) NOT NULL, phone VARCHAR(32), email VARCHAR(128), FOREIGN KEY (user_id) REFERENCES users(id), FOREIGN KEY (department_id) REFERENCES departments(id))");
            statement.execute("CREATE TABLE student_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, old_status VARCHAR(16), new_status VARCHAR(16) NOT NULL, reason VARCHAR(255) NOT NULL, changed_by_user_id BIGINT NOT NULL, FOREIGN KEY (student_id) REFERENCES student_profiles(id), FOREIGN KEY (changed_by_user_id) REFERENCES users(id))");
        }
    }

    private void seedReferences() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            for (UserRole role : UserRole.values()) {
                statement.executeUpdate("INSERT INTO roles (role_code, role_name) VALUES ('" + role.name() + "', '" + role.name() + "')");
            }
            statement.executeUpdate("INSERT INTO departments VALUES (10, 'CS', '计算机学院', TRUE)");
            statement.executeUpdate("INSERT INTO majors VALUES (20, 10, 'SE', '软件工程', TRUE)");
            statement.executeUpdate("INSERT INTO administrative_classes VALUES (100, 20, 'SE2601', '软件工程2601班', 2026, TRUE)");
            statement.executeUpdate("INSERT INTO users (id, username, password_hash, password_salt, display_name, enabled, force_password_change) VALUES (900, 'admin', 'hash', 'salt', '系统管理员', TRUE, FALSE)");
            statement.executeUpdate("INSERT INTO user_roles SELECT 900, id FROM roles WHERE role_code = 'SUPER_ADMIN'");
        }
    }

    private void insertSuperAdministrator() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (username, password_hash, password_salt, display_name, enabled, force_password_change) VALUES ('other-admin', 'hash', 'salt', '另一管理员', TRUE, FALSE)");
            statement.executeUpdate("INSERT INTO user_roles SELECT u.id, r.id FROM users u JOIN roles r ON r.role_code = 'SUPER_ADMIN' WHERE u.username = 'other-admin'");
        }
    }

    private int count(String table, String where) throws SQLException {
        return (int) scalarLong("SELECT COUNT(*) FROM " + table + " WHERE " + where);
    }

    private int countUnchecked(String table, String where) {
        try {
            return count(table, where);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private boolean scalarBoolean(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private Set<String> roleCodes(long userId) throws SQLException {
        java.util.LinkedHashSet<String> roles = new java.util.LinkedHashSet<>();
        String sql = "SELECT r.role_code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?";
        try (Connection connection = connections.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    roles.add(result.getString(1));
                }
            }
        }
        return Set.copyOf(roles);
    }

    private int notificationCount(long userId) throws SQLException {
        return count("notifications", "recipient_user_id = " + userId);
    }

    private String lastNotificationType(long userId) throws SQLException {
        return scalarString("SELECT notification_type FROM notifications"
                + " WHERE recipient_user_id = " + userId + " ORDER BY id DESC LIMIT 1");
    }

    private static final class FailingNotificationWriter implements NotificationWriter {
        @Override
        public void insert(Connection connection, NotificationDraft draft) throws SQLException {
            throw new SQLException("notification failed");
        }

        @Override
        public void insertBatch(Connection connection, java.util.List<NotificationDraft> drafts)
                throws SQLException {
            throw new SQLException("notification failed");
        }
    }
}
