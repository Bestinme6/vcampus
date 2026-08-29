package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BankRepository implements BankStore {
    private static final String ACCOUNT_COLUMNS = "a.id,a.user_id,u.username,u.display_name,"
            + "a.balance,a.status,a.created_at,a.updated_at";
    private final ConnectionFactory connections;
    private final NotificationWriter notifications;

    public BankRepository(ConnectionFactory connections, NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public BankAccountRecord account(long userId) throws SQLException {
        if (userId < 1) {
            throw new IllegalArgumentException("用户无效");
        }
        try (Connection connection = connections.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO bank_accounts(user_id) VALUES (?) "
                            + "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)")) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
            return findAccount(connection, userId);
        }
    }

    @Override
    public AccountPage searchAccounts(AccountQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String where = " WHERE (?='' OR u.username LIKE ? OR u.display_name LIKE ?)"
                + " AND (? IS NULL OR a.status=?)";
        String keyword = "%" + query.keyword() + "%";
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM bank_accounts a JOIN users u ON u.id=a.user_id" + where)) {
                bindAccountQuery(statement, query, keyword);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<BankAccountRecord> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + ACCOUNT_COLUMNS
                            + " FROM bank_accounts a JOIN users u ON u.id=a.user_id"
                            + where + " ORDER BY a.id LIMIT ? OFFSET ?")) {
                bindAccountQuery(statement, query, keyword);
                statement.setInt(6, query.pageSize());
                statement.setInt(7, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(mapAccount(result));
                    }
                }
            }
            return new AccountPage(rows, query.page(), query.pageSize(), total);
        }
    }

    @Override
    public LedgerPage searchLedger(LedgerQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String where = " WHERE a.user_id=? AND (? IS NULL OR e.entry_type=?)";
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM bank_ledger_entries e "
                            + "JOIN bank_accounts a ON a.id=e.account_id" + where)) {
                bindLedgerQuery(statement, query);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<BankLedgerRecord> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT e.id,e.account_id,e.entry_type,e.direction,e.amount,e.balance_after,"
                            + "e.reference_no,e.counterparty_user_id,e.operator_user_id,"
                            + "e.description,e.created_at FROM bank_ledger_entries e "
                            + "JOIN bank_accounts a ON a.id=e.account_id" + where
                            + " ORDER BY e.created_at DESC,e.id DESC LIMIT ? OFFSET ?")) {
                bindLedgerQuery(statement, query);
                statement.setInt(4, query.pageSize());
                statement.setInt(5, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(mapLedger(result));
                    }
                }
            }
            return new LedgerPage(rows, query.page(), query.pageSize(), total);
        }
    }

    private BankAccountRecord findAccount(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ACCOUNT_COLUMNS + " FROM bank_accounts a "
                        + "JOIN users u ON u.id=a.user_id WHERE a.user_id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Bank account creation failed");
                }
                return mapAccount(result);
            }
        }
    }

    private void bindAccountQuery(
            PreparedStatement statement, AccountQuery query, String keyword) throws SQLException {
        statement.setString(1, query.keyword());
        statement.setString(2, keyword);
        statement.setString(3, keyword);
        String status = query.status() == null ? null : query.status().name();
        statement.setString(4, status);
        statement.setString(5, status);
    }

    private void bindLedgerQuery(PreparedStatement statement, LedgerQuery query)
            throws SQLException {
        statement.setLong(1, query.accountUserId());
        String type = query.type() == null ? null : query.type().name();
        statement.setString(2, type);
        statement.setString(3, type);
    }

    private BankAccountRecord mapAccount(ResultSet result) throws SQLException {
        return new BankAccountRecord(
                result.getLong("id"),
                result.getLong("user_id"),
                result.getString("username"),
                result.getString("display_name"),
                result.getBigDecimal("balance").setScale(2),
                BankAccountStatus.valueOf(result.getString("status")),
                instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")));
    }

    private BankLedgerRecord mapLedger(ResultSet result) throws SQLException {
        return new BankLedgerRecord(
                result.getLong("id"),
                result.getLong("account_id"),
                BankLedgerType.valueOf(result.getString("entry_type")),
                BankLedgerDirection.valueOf(result.getString("direction")),
                result.getBigDecimal("amount").setScale(2),
                result.getBigDecimal("balance_after").setScale(2),
                result.getString("reference_no"),
                nullableLong(result, "counterparty_user_id"),
                nullableLong(result, "operator_user_id"),
                result.getString("description"),
                instant(result.getTimestamp("created_at")));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
