package com.vcampus.server.database;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.UserRole;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.server.model.AccountSummary;
import com.vcampus.server.security.PasswordHasher.PasswordHash;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class AccountRepository implements AccountStore {
    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;

    public AccountRepository(
            ConnectionFactory connectionFactory, NotificationWriter notifications) {
        this.connectionFactory = connectionFactory;
        this.notifications = notifications;
    }

    @Override
    public AccountPage search(
            String keyword, UserRole identity, Boolean enabled, int page, int pageSize)
            throws SQLException {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(100, pageSize));
        try (Connection connection = connectionFactory.openConnection()) {
            int total = countAccounts(connection, normalizedKeyword, identity, enabled);
            List<Long> userIds = pageUserIds(
                    connection, normalizedKeyword, identity, enabled,
                    safePage, safePageSize);
            List<AccountSummary> rows = new ArrayList<>();
            for (Long userId : userIds) {
                loadSummary(connection, userId).ifPresent(rows::add);
            }
            return new AccountPage(rows, safePage, safePageSize, total);
        }
    }

    @Override
    public AccountReferences referenceData() throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            List<ReferenceItem> departments = new ArrayList<>();
            List<ReferenceItem> majors = new ArrayList<>();
            List<ReferenceItem> classes = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, department_code, department_name FROM departments WHERE enabled = TRUE ORDER BY department_code");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    departments.add(new ReferenceItem(
                            result.getLong("id"), 0L, result.getString("department_code"),
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
            return new AccountReferences(departments, majors, classes);
        }
    }

    @Override
    public long createStudent(
            CreateStudentAccount command, PasswordHash password, long operatorUserId)
            throws SQLException {
        RoleCompositionPolicy.requireValid(command.roles());
        if (RoleCompositionPolicy.baseIdentity(command.roles()) != UserRole.STUDENT) {
            throw new IllegalArgumentException("学生账号必须使用学生基础身份");
        }
        return inTransaction(connection -> {
            requireStudentHierarchy(connection, command);
            long userId = insertUser(
                    connection, command.studentNumber(), command.fullName(), password);
            assignRoles(connection, userId, command.roles());
            long studentId = insertStudentProfile(connection, userId, command);
            insertInitialStatus(connection, studentId, operatorUserId);
            return studentId;
        });
    }

    @Override
    public long createTeacher(CreateTeacherAccount command, PasswordHash password) throws SQLException {
        RoleCompositionPolicy.requireValid(command.roles());
        if (RoleCompositionPolicy.baseIdentity(command.roles()) != UserRole.TEACHER) {
            throw new IllegalArgumentException("教师账号必须使用教师基础身份");
        }
        return inTransaction(connection -> {
            requireDepartment(connection, command.departmentId());
            long userId = insertUser(
                    connection, command.teacherNumber(), command.fullName(), password);
            assignRoles(connection, userId, command.roles());
            return insertTeacherProfile(connection, userId, command);
        });
    }

    @Override
    public Optional<AccountSummary> findManageableById(long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            return loadSummary(connection, userId);
        }
    }

    @Override
    public MutationResult replaceAdministrativeRoles(
            long userId, UserRole baseIdentity, Set<UserRole> roles,
            long operatorUserId, String operatorDisplayName) throws SQLException {
        Set<UserRole> completeRoles = new LinkedHashSet<>();
        completeRoles.add(baseIdentity);
        completeRoles.addAll(roles);
        RoleCompositionPolicy.requireValid(completeRoles);
        return inTransaction(connection -> {
            if (lockManageableEnabled(connection, userId).isEmpty()) {
                return MutationResult.NOT_FOUND;
            }
            Optional<AccountSummary> found = loadSummary(connection, userId);
            if (found.isEmpty() || found.get().baseIdentity() != baseIdentity) {
                return MutationResult.NOT_FOUND;
            }
            Set<UserRole> previousRoles = found.get().administrativeRoles();
            if (previousRoles.equals(roles)) {
                return MutationResult.UNCHANGED;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM user_roles WHERE user_id = ?")) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
            assignRoles(connection, userId, completeRoles);
            Set<UserRole> added = new LinkedHashSet<>(roles);
            added.removeAll(previousRoles);
            Set<UserRole> removed = new LinkedHashSet<>(previousRoles);
            removed.removeAll(roles);
            notifications.insert(connection, new NotificationDraft(
                    userId, operatorUserId, NotificationType.ROLES_CHANGED,
                    NotificationSource.ACCOUNT_SECURITY, "管理员角色变更",
                    roleChangeContent(operatorDisplayName, added, removed),
                    NotificationTarget.NONE, userId));
            return MutationResult.CHANGED;
        });
    }

    @Override
    public MutationResult setEnabled(
            long userId, boolean enabled,
            long operatorUserId, String operatorDisplayName) throws SQLException {
        return inTransaction(connection -> {
            Optional<Boolean> current = lockManageableEnabled(connection, userId);
            if (current.isEmpty()) {
                return MutationResult.NOT_FOUND;
            }
            if (current.get() == enabled) {
                return MutationResult.UNCHANGED;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET enabled = ? WHERE id = ?")) {
                statement.setBoolean(1, enabled);
                statement.setLong(2, userId);
                statement.executeUpdate();
            }
            NotificationType type = enabled
                    ? NotificationType.ACCOUNT_ENABLED : NotificationType.ACCOUNT_DISABLED;
            String operation = enabled ? "启用" : "停用";
            notifications.insert(connection, new NotificationDraft(
                    userId, operatorUserId, type, NotificationSource.ACCOUNT_SECURITY,
                    "账号已" + operation,
                    operatorDisplayName + "已" + operation + "您的账号。",
                    NotificationTarget.NONE, userId));
            return MutationResult.CHANGED;
        });
    }

    @Override
    public MutationResult resetPassword(
            long userId, PasswordHash password,
            long operatorUserId, String operatorDisplayName) throws SQLException {
        return inTransaction(connection -> {
            if (lockManageableEnabled(connection, userId).isEmpty()) {
                return MutationResult.NOT_FOUND;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE users
                       SET password_hash = ?, password_salt = ?, force_password_change = TRUE
                     WHERE id = ?
                    """)) {
                statement.setString(1, password.hash());
                statement.setString(2, password.salt());
                statement.setLong(3, userId);
                statement.executeUpdate();
            }
            notifications.insert(connection, new NotificationDraft(
                    userId, operatorUserId, NotificationType.PASSWORD_RESET,
                    NotificationSource.ACCOUNT_SECURITY, "密码重置通知",
                    operatorDisplayName + "已重置您的账号密码。请使用临时密码登录并立即修改密码。",
                    NotificationTarget.NONE, userId));
            return MutationResult.CHANGED;
        });
    }

    private Optional<Boolean> lockManageableEnabled(Connection connection, long userId)
            throws SQLException {
        String sql = """
                SELECT u.enabled
                  FROM users u
                 WHERE u.id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.role_code = 'SUPER_ADMIN')
                 FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(result.getBoolean("enabled"))
                        : Optional.empty();
            }
        }
    }

    private String roleChangeContent(
            String operatorDisplayName, Set<UserRole> added, Set<UserRole> removed) {
        List<String> changes = new ArrayList<>();
        if (!added.isEmpty()) {
            changes.add("新增：" + roleLabels(added));
        }
        if (!removed.isEmpty()) {
            changes.add("取消：" + roleLabels(removed));
        }
        return operatorDisplayName + "已变更您的管理员角色。" + String.join("；", changes) + "。";
    }

    private String roleLabels(Set<UserRole> roles) {
        return roles.stream().map(this::roleLabel).sorted().collect(Collectors.joining("、"));
    }

    private String roleLabel(UserRole role) {
        return switch (role) {
            case STUDENT -> "学生";
            case TEACHER -> "教师";
            case SUPER_ADMIN -> "超级管理员";
            case STUDENT_ADMIN -> "学籍管理员";
            case ACADEMIC_ADMIN -> "教务管理员";
            case LIBRARY_ADMIN -> "图书管理员";
            case SHOP_ADMIN -> "商店管理员";
            case BANK_ADMIN -> "银行管理员";
            case FORUM_ADMIN -> "论坛管理员";
        };
    }

    private int countAccounts(
            Connection connection, String keyword, UserRole identity, Boolean enabled)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM users u
                 WHERE NOT EXISTS (
                       SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.role_code = 'SUPER_ADMIN')
                   AND EXISTS (
                       SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.role_code IN ('STUDENT', 'TEACHER'))
                """);
        List<Object> parameters = appendFilters(sql, keyword, identity, enabled);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private List<Long> pageUserIds(
            Connection connection, String keyword, UserRole identity, Boolean enabled,
            int page, int pageSize) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id
                  FROM users u
                 WHERE NOT EXISTS (
                       SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.role_code = 'SUPER_ADMIN')
                   AND EXISTS (
                       SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.role_code IN ('STUDENT', 'TEACHER'))
                """);
        List<Object> parameters = appendFilters(sql, keyword, identity, enabled);
        sql.append(" ORDER BY u.id DESC LIMIT ? OFFSET ?");
        parameters.add(pageSize);
        parameters.add((page - 1) * pageSize);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
                return ids;
            }
        }
    }

    private List<Object> appendFilters(
            StringBuilder sql, String keyword, UserRole identity, Boolean enabled) {
        List<Object> parameters = new ArrayList<>();
        if (!keyword.isBlank()) {
            sql.append(" AND (u.username LIKE ? OR u.display_name LIKE ?)");
            String pattern = "%" + keyword + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (identity != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.role_code = ?)");
            parameters.add(identity.name());
        }
        if (enabled != null) {
            sql.append(" AND u.enabled = ?");
            parameters.add(enabled);
        }
        return parameters;
    }

    private Optional<AccountSummary> loadSummary(Connection connection, long userId) throws SQLException {
        String sql = """
                SELECT u.id, u.username, u.display_name, u.enabled,
                       u.force_password_change, u.last_login_at, r.role_code
                  FROM users u
                  JOIN user_roles ur ON ur.user_id = u.id
                  JOIN roles r ON r.id = ur.role_id
                 WHERE u.id = ?
                 ORDER BY r.role_code
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String username = result.getString("username");
                String displayName = result.getString("display_name");
                boolean enabled = result.getBoolean("enabled");
                boolean forcePasswordChange = result.getBoolean("force_password_change");
                Timestamp timestamp = result.getTimestamp("last_login_at");
                Instant lastLoginAt = timestamp == null ? null : timestamp.toInstant();
                Set<UserRole> roles = new LinkedHashSet<>();
                do {
                    roles.add(parseRole(result.getString("role_code")));
                } while (result.next());
                if (roles.contains(UserRole.SUPER_ADMIN)) {
                    return Optional.empty();
                }
                RoleCompositionPolicy.requireValid(roles);
                return Optional.of(new AccountSummary(
                        userId, username, displayName,
                        RoleCompositionPolicy.baseIdentity(roles),
                        RoleCompositionPolicy.administrativeRoles(roles),
                        enabled, forcePasswordChange, lastLoginAt));
            }
        }
    }

    private void requireStudentHierarchy(Connection connection, CreateStudentAccount command)
            throws SQLException {
        String sql = """
                SELECT 1
                  FROM departments d
                  JOIN majors m ON m.department_id = d.id
                  JOIN administrative_classes c ON c.major_id = m.id
                 WHERE d.id = ? AND m.id = ? AND c.id = ? AND c.enrollment_year = ?
                   AND d.enabled = TRUE AND m.enabled = TRUE AND c.enabled = TRUE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, command.departmentId());
            statement.setLong(2, command.majorId());
            statement.setLong(3, command.classId());
            statement.setInt(4, command.enrollmentYear());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("学院、专业、班级或入学年份不匹配");
                }
            }
        }
    }

    private void requireDepartment(Connection connection, long departmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM departments WHERE id = ? AND enabled = TRUE")) {
            statement.setLong(1, departmentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("学院不存在或已停用");
                }
            }
        }
    }

    private long insertUser(
            Connection connection, String username, String displayName, PasswordHash password)
            throws SQLException {
        String sql = """
                INSERT INTO users
                    (username, password_hash, password_salt, display_name, enabled, force_password_change)
                VALUES (?, ?, ?, ?, TRUE, TRUE)
                """;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, password.hash());
            statement.setString(3, password.salt());
            statement.setString(4, displayName);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("数据库未返回账号ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private void assignRoles(Connection connection, long userId, Set<UserRole> roles)
            throws SQLException {
        String findSql = "SELECT id FROM roles WHERE role_code = ?";
        String insertSql = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)";
        try (PreparedStatement find = connection.prepareStatement(findSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (UserRole role : roles) {
                find.setString(1, role.name());
                try (ResultSet result = find.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("角色尚未初始化: " + role.name());
                    }
                    insert.setLong(1, userId);
                    insert.setLong(2, result.getLong(1));
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
    }

    private long insertStudentProfile(
            Connection connection, long userId, CreateStudentAccount command) throws SQLException {
        String sql = """
                INSERT INTO student_profiles
                    (user_id, student_number, full_name, gender, birth_date,
                     department_id, major_id, class_id, enrollment_year, status,
                     phone, email, address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENROLLED', ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, command.studentNumber());
            statement.setString(3, command.fullName());
            statement.setString(4, command.gender().name());
            if (command.birthDate() == null) {
                statement.setNull(5, Types.DATE);
            } else {
                statement.setDate(5, Date.valueOf(command.birthDate()));
            }
            statement.setLong(6, command.departmentId());
            statement.setLong(7, command.majorId());
            statement.setLong(8, command.classId());
            statement.setInt(9, command.enrollmentYear());
            setNullable(statement, 10, command.phone());
            setNullable(statement, 11, command.email());
            setNullable(statement, 12, command.address());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("数据库未返回学生档案ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private long insertTeacherProfile(
            Connection connection, long userId, CreateTeacherAccount command) throws SQLException {
        String sql = """
                INSERT INTO teacher_profiles
                    (user_id, teacher_number, full_name, department_id,
                     professional_title, phone, email)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, command.teacherNumber());
            statement.setString(3, command.fullName());
            statement.setLong(4, command.departmentId());
            statement.setString(5, command.professionalTitle());
            setNullable(statement, 6, command.phone());
            setNullable(statement, 7, command.email());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("数据库未返回教师档案ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertInitialStatus(Connection connection, long studentId, long operatorUserId)
            throws SQLException {
        String sql = """
                INSERT INTO student_status_history
                    (student_id, old_status, new_status, reason, changed_by_user_id)
                VALUES (?, NULL, 'ENROLLED', '创建学生档案', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, studentId);
            statement.setLong(2, operatorUserId);
            statement.executeUpdate();
        }
    }

    private UserRole parseRole(String value) throws SQLException {
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("数据库包含未知角色: " + value, exception);
        }
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private void setNullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
