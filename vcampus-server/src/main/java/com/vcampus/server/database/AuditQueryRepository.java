package com.vcampus.server.database;

import com.vcampus.common.model.AuditLogEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuditQueryRepository implements AuditQueryStore {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ConnectionFactory connectionFactory;

    public AuditQueryRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public AuditPage search(AuditQuery query) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        appendFilters(where, parameters, query);
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id" + where)) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    total = rows.getInt(1);
                }
            }
            String sql = """
                    SELECT a.id,a.user_id,u.username,u.display_name,a.action_code,a.result_code,
                           a.client_address,a.created_at
                      FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id
                    """ + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?";
            List<Object> pageParameters = new ArrayList<>(parameters);
            pageParameters.add(query.pageSize());
            pageParameters.add((query.page() - 1) * query.pageSize());
            List<AuditLogEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, pageParameters);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long rawUserId = rows.getLong("user_id");
                        Long userId = rows.wasNull() ? null : rawUserId;
                        entries.add(new AuditLogEntry(rows.getLong("id"), userId,
                                text(rows.getString("username")), text(rows.getString("display_name")),
                                rows.getString("action_code"), rows.getString("result_code"),
                                text(rows.getString("client_address")), rows.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return new AuditPage(entries, query.page(), query.pageSize(), total);
        }
    }

    private void appendFilters(StringBuilder where, List<Object> parameters, AuditQuery query) {
        if (!query.userKeyword().isBlank()) {
            where.append(" AND (LOWER(u.username) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(query.userKeyword().toLowerCase(Locale.ROOT)) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (!query.actionPrefix().isBlank()) {
            where.append(" AND a.action_code LIKE ? ESCAPE '!'");
            parameters.add(escapeLike(query.actionPrefix()) + "%");
        }
        if (!query.resultCode().isBlank()) {
            where.append(" AND a.result_code = ?");
            parameters.add(query.resultCode());
        }
        if (query.fromDate() != null) {
            where.append(" AND a.created_at >= ?");
            parameters.add(campusBoundary(query.fromDate()));
        }
        if (query.toDate() != null) {
            where.append(" AND a.created_at < ?");
            parameters.add(campusBoundary(query.toDate().plusDays(1)));
        }
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Instant instant) {
                statement.setTimestamp(index + 1, Timestamp.from(instant));
            } else if (value instanceof LocalDateTime time) {
                statement.setTimestamp(index + 1, Timestamp.valueOf(time));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    static Instant campusBoundary(LocalDate date) {
        return date.atStartOfDay(CAMPUS_ZONE).toInstant();
    }

    static Timestamp campusTimestamp(LocalDate date) {
        return Timestamp.from(campusBoundary(date));
    }
}
