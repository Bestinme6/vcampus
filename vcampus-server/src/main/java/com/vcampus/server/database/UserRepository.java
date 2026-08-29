package com.vcampus.server.database;

import com.vcampus.common.model.UserRole;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.PasswordHasher.PasswordHash;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class UserRepository implements UserAccountStore {
    private static final String FIND_BY_USERNAME = """
            SELECT u.id, u.username, u.password_hash, u.password_salt,
                   u.display_name, u.enabled, u.force_password_change, r.role_code
              FROM users u
              LEFT JOIN user_roles ur ON ur.user_id = u.id
              LEFT JOIN roles r ON r.id = ur.role_id
             WHERE BINARY u.username = BINARY ?
             ORDER BY r.role_code
            """;

    private final ConnectionFactory connectionFactory;

    public UserRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Optional<UserAccount> findByUsername(String username) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_USERNAME)) {
            statement.setString(1, username);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                long id = results.getLong("id");
                String storedUsername = results.getString("username");
                String passwordHash = results.getString("password_hash");
                String passwordSalt = results.getString("password_salt");
                String displayName = results.getString("display_name");
                boolean enabled = results.getBoolean("enabled");
                boolean forcePasswordChange = results.getBoolean("force_password_change");
                Set<UserRole> roles = new LinkedHashSet<>();
                readRole(results, roles);
                while (results.next()) {
                    readRole(results, roles);
                }
                return Optional.of(new UserAccount(
                        id, storedUsername, passwordHash, passwordSalt, displayName,
                        enabled, forcePasswordChange, roles));
            }
        }
    }

    public void updateLastLogin(long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    @Override
    public boolean updatePassword(
            long userId, PasswordHash password, boolean forcePasswordChange) throws SQLException {
        String sql = """
                UPDATE users
                   SET password_hash = ?, password_salt = ?, force_password_change = ?
                 WHERE id = ?
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, password.hash());
            statement.setString(2, password.salt());
            statement.setBoolean(3, forcePasswordChange);
            statement.setLong(4, userId);
            return statement.executeUpdate() == 1;
        }
    }

    public long createUser(
            String username,
            String displayName,
            PasswordHash password,
            boolean forcePasswordChange,
            Set<UserRole> roles) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long userId = insertUser(connection, username, displayName, password, forcePasswordChange);
                assignRoles(connection, userId, roles);
                connection.commit();
                return userId;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private long insertUser(
            Connection connection,
            String username,
            String displayName,
            PasswordHash password,
            boolean forcePasswordChange) throws SQLException {
        String sql = """
                INSERT INTO users
                    (username, password_hash, password_salt, display_name, enabled, force_password_change)
                VALUES (?, ?, ?, ?, TRUE, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, password.hash());
            statement.setString(3, password.salt());
            statement.setString(4, displayName);
            statement.setBoolean(5, forcePasswordChange);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return the new user id");
                }
                return keys.getLong(1);
            }
        }
    }

    private void assignRoles(Connection connection, long userId, Set<UserRole> roles) throws SQLException {
        String findRole = "SELECT id FROM roles WHERE role_code = ?";
        String linkRole = "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)";
        try (PreparedStatement find = connection.prepareStatement(findRole);
             PreparedStatement link = connection.prepareStatement(linkRole)) {
            for (UserRole role : roles) {
                find.setString(1, role.name());
                try (ResultSet result = find.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Role has not been seeded: " + role.name());
                    }
                    link.setLong(1, userId);
                    link.setLong(2, result.getLong("id"));
                    link.addBatch();
                }
            }
            link.executeBatch();
        }
    }

    private void readRole(ResultSet results, Set<UserRole> roles) throws SQLException {
        String roleCode = results.getString("role_code");
        if (roleCode == null) {
            return;
        }
        try {
            roles.add(UserRole.valueOf(roleCode));
        } catch (IllegalArgumentException unknownRole) {
            throw new SQLException("Unknown role code in database: " + roleCode, unknownRole);
        }
    }
}
