package com.vcampus.server.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public final class AuditRepository implements AuditStore {
    private final ConnectionFactory connectionFactory;

    public AuditRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public void record(Long userId, String action, String result, String clientAddress) {
        String sql = """
                INSERT INTO audit_logs (user_id, action_code, result_code, client_address)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (userId == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, userId);
            }
            statement.setString(2, action);
            statement.setString(3, result);
            statement.setString(4, clientAddress);
            statement.executeUpdate();
        } catch (SQLException exception) {
            System.err.println("Unable to write audit log: " + exception.getMessage());
        }
    }
}
