package com.vcampus.server.database;

import com.vcampus.server.model.TeacherProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

public final class TeacherRepository implements TeacherProfileStore {
    private static final String FIND_BY_USER_ID = """
            SELECT tp.id, tp.user_id, tp.teacher_number, tp.full_name,
                   d.department_name, tp.professional_title, tp.phone, tp.email
              FROM teacher_profiles tp
              JOIN departments d ON d.id = tp.department_id
             WHERE tp.user_id = ?
            """;

    private final ConnectionFactory connectionFactory;

    public TeacherRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Optional<TeacherProfile> findByUserId(long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_USER_ID)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TeacherProfile(
                        result.getLong("id"),
                        result.getLong("user_id"),
                        result.getString("teacher_number"),
                        result.getString("full_name"),
                        result.getString("department_name"),
                        result.getString("professional_title"),
                        result.getString("phone"),
                        result.getString("email")));
            }
        }
    }

    @Override
    public boolean updateContact(long userId, String phone, String email) throws SQLException {
        String sql = "UPDATE teacher_profiles SET phone = ?, email = ? WHERE user_id = ?";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullable(statement, 1, phone);
            setNullable(statement, 2, email);
            statement.setLong(3, userId);
            return statement.executeUpdate() == 1;
        }
    }

    private void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
