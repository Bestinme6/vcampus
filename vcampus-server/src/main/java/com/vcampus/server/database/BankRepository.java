package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BankRepository implements BankStore, BankPaymentWriter {
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
        String where = " WHERE (? IS NULL OR a.user_id=?) AND (? IS NULL OR e.entry_type=?)";
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
                statement.setInt(5, query.pageSize());
                statement.setInt(6, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(mapLedger(result));
                    }
                }
            }
            return new LedgerPage(rows, query.page(), query.pageSize(), total);
        }
    }

    @Override
    public TopUpResult topUp(
            long operatorUserId, long targetUserId, BigDecimal amount, String operationId)
            throws SQLException {
        requirePositiveId(operatorUserId);
        requirePositiveId(targetUserId);
        BigDecimal normalized = normalizeAmount(amount);
        String reference = requireReference(operationId);
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, targetUserId);
                LockedAccount account = lockAccount(connection, targetUserId);
                BigDecimal existing = existingBalanceAfter(
                        connection, account.id(), BankLedgerType.ADMIN_TOPUP, reference);
                if (existing != null) {
                    connection.commit();
                    return new TopUpResult(account.id(), existing, reference, true);
                }
                BigDecimal balanceAfter = account.balance().add(normalized);
                updateBalance(connection, account.id(), balanceAfter);
                insertLedger(connection, account.id(), BankLedgerType.ADMIN_TOPUP,
                        BankLedgerDirection.CREDIT, normalized, balanceAfter, reference,
                        null, operatorUserId, "银行管理员充值");
                notifications.insert(connection, new NotificationDraft(
                        targetUserId,
                        operatorUserId,
                        NotificationType.BANK_ACCOUNT_TOPPED_UP,
                        NotificationSource.BANK,
                        "您的虚拟银行账户已充值",
                        "银行管理员已为您的账户充值 " + MoneyPolicy.format(normalized) + " 元。",
                        NotificationTarget.BANK_LEDGER,
                        account.id()));
                connection.commit();
                return new TopUpResult(account.id(), balanceAfter, reference, false);
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public StatusResult setStatus(
            long operatorUserId, long targetUserId, BankAccountStatus status)
            throws SQLException {
        requirePositiveId(operatorUserId);
        requirePositiveId(targetUserId);
        Objects.requireNonNull(status, "status");
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccount(connection, targetUserId);
                LockedAccount account = lockAccount(connection, targetUserId);
                if (account.status() == status) {
                    connection.commit();
                    return new StatusResult(account.id(), status, false);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE bank_accounts SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    statement.setString(1, status.name());
                    statement.setLong(2, account.id());
                    statement.executeUpdate();
                }
                String label = status == BankAccountStatus.FROZEN ? "冻结" : "解冻";
                notifications.insert(connection, new NotificationDraft(
                        targetUserId,
                        operatorUserId,
                        NotificationType.BANK_ACCOUNT_STATUS_CHANGED,
                        NotificationSource.BANK,
                        "您的虚拟银行账户状态已更新",
                        "银行管理员已将您的账户" + label + "。",
                        NotificationTarget.BANK_LEDGER,
                        account.id()));
                connection.commit();
                return new StatusResult(account.id(), status, true);
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public TransferResult transfer(
            long senderUserId, String recipientUsername, BigDecimal amount, String operationId)
            throws SQLException {
        requirePositiveId(senderUserId);
        String username = recipientUsername == null ? "" : recipientUsername.trim();
        if (username.isEmpty()) {
            throw new BankRuleException("收款用户不存在或已停用");
        }
        BigDecimal normalized = normalizeAmount(amount);
        String reference = requireReference(operationId);
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UserIdentity recipient = enabledUserByUsername(connection, username);
                if (recipient == null) {
                    throw new BankRuleException("收款用户不存在或已停用");
                }
                if (recipient.userId() == senderUserId) {
                    throw new BankRuleException("不能向自己转账");
                }
                long firstUserId = Math.min(senderUserId, recipient.userId());
                long secondUserId = Math.max(senderUserId, recipient.userId());
                ensureAccount(connection, firstUserId);
                ensureAccount(connection, secondUserId);
                long senderAccountId = accountId(connection, senderUserId);
                long recipientAccountId = accountId(connection, recipient.userId());
                List<LockedAccount> locked = lockAccounts(
                        connection, senderAccountId, recipientAccountId);
                LockedAccount sender = locked.stream()
                        .filter(value -> value.id() == senderAccountId).findFirst().orElseThrow();
                LockedAccount receiver = locked.stream()
                        .filter(value -> value.id() == recipientAccountId).findFirst().orElseThrow();

                ExistingLedger existing = existingLedger(
                        connection, sender.id(), BankLedgerType.TRANSFER_OUT, reference);
                if (existing != null) {
                    if (existing.amount().compareTo(normalized) != 0
                            || !Objects.equals(existing.counterpartyUserId(), recipient.userId())) {
                        throw new BankRuleException("该业务已经处理，请勿重复提交");
                    }
                    ExistingLedger incoming = existingLedger(
                            connection, receiver.id(), BankLedgerType.TRANSFER_IN, reference);
                    if (incoming == null) {
                        throw new SQLException("Incomplete transfer ledger pair");
                    }
                    connection.commit();
                    return new TransferResult(reference, existing.balanceAfter(),
                            incoming.balanceAfter(), true);
                }
                if (sender.status() == BankAccountStatus.FROZEN) {
                    throw new BankRuleException("账户已冻结，不能转账");
                }
                if (sender.balance().compareTo(normalized) < 0) {
                    throw new BankRuleException("余额不足");
                }
                BigDecimal senderAfter = sender.balance().subtract(normalized);
                BigDecimal recipientAfter = receiver.balance().add(normalized);
                updateBalance(connection, sender.id(), senderAfter);
                updateBalance(connection, receiver.id(), recipientAfter);
                insertLedger(connection, sender.id(), BankLedgerType.TRANSFER_OUT,
                        BankLedgerDirection.DEBIT, normalized, senderAfter, reference,
                        recipient.userId(), null, "转账给" + recipient.displayName());
                insertLedger(connection, receiver.id(), BankLedgerType.TRANSFER_IN,
                        BankLedgerDirection.CREDIT, normalized, recipientAfter, reference,
                        senderUserId, null, "收到转账");
                notifications.insert(connection, new NotificationDraft(
                        recipient.userId(),
                        senderUserId,
                        NotificationType.BANK_TRANSFER_RECEIVED,
                        NotificationSource.BANK,
                        "您的虚拟银行账户收到一笔转账",
                        userDisplayName(connection, senderUserId) + "向您转账 "
                                + MoneyPolicy.format(normalized) + " 元。",
                        NotificationTarget.BANK_LEDGER,
                        receiver.id()));
                connection.commit();
                return new TransferResult(reference, senderAfter, recipientAfter, false);
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public PaymentResult debitForShop(
            Connection connection,
            long userId,
            BigDecimal amount,
            String referenceNo,
            String description) throws SQLException {
        return writeShopMovement(connection, userId, amount, referenceNo, description, false);
    }

    @Override
    public PaymentResult refundForShop(
            Connection connection,
            long userId,
            BigDecimal amount,
            String referenceNo,
            String description) throws SQLException {
        return writeShopMovement(connection, userId, amount, referenceNo, description, true);
    }

    private PaymentResult writeShopMovement(
            Connection connection,
            long userId,
            BigDecimal amount,
            String referenceNo,
            String description,
            boolean refund) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requirePositiveId(userId);
        BigDecimal normalized = normalizeAmount(amount);
        String reference = requireReference(referenceNo);
        String safeDescription = description == null ? "" : description.trim();
        if (safeDescription.isEmpty() || safeDescription.length() > 255) {
            throw new IllegalArgumentException("流水说明无效");
        }
        ensureAccount(connection, userId);
        LockedAccount account = lockAccount(connection, userId);
        BankLedgerType type = refund ? BankLedgerType.SHOP_REFUND : BankLedgerType.SHOP_PAYMENT;
        ExistingLedger existing = existingLedger(connection, account.id(), type, reference);
        if (existing != null) {
            if (existing.amount().compareTo(normalized) != 0) {
                throw new BankRuleException("该业务已经处理，请勿重复提交");
            }
            return new PaymentResult(account.id(), existing.balanceAfter(), true);
        }
        if (!refund && account.status() == BankAccountStatus.FROZEN) {
            throw new BankRuleException("账户已冻结，不能支付");
        }
        if (!refund && account.balance().compareTo(normalized) < 0) {
            throw new BankRuleException("余额不足");
        }
        BigDecimal balanceAfter = refund
                ? account.balance().add(normalized)
                : account.balance().subtract(normalized);
        updateBalance(connection, account.id(), balanceAfter);
        insertLedger(connection, account.id(), type,
                refund ? BankLedgerDirection.CREDIT : BankLedgerDirection.DEBIT,
                normalized, balanceAfter, reference, null, null, safeDescription);
        return new PaymentResult(account.id(), balanceAfter, false);
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

    private void ensureAccount(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bank_accounts(user_id) VALUES (?) "
                        + "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    private LockedAccount lockAccount(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,balance,status FROM bank_accounts WHERE user_id=? FOR UPDATE")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Bank account not found after creation");
                }
                return new LockedAccount(
                        result.getLong("id"),
                        result.getBigDecimal("balance").setScale(2),
                        BankAccountStatus.valueOf(result.getString("status")));
            }
        }
    }

    private BigDecimal existingBalanceAfter(
            Connection connection, long accountId, BankLedgerType type, String reference)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_after FROM bank_ledger_entries "
                        + "WHERE account_id=? AND entry_type=? AND reference_no=?")) {
            statement.setLong(1, accountId);
            statement.setString(2, type.name());
            statement.setString(3, reference);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBigDecimal(1).setScale(2) : null;
            }
        }
    }

    private ExistingLedger existingLedger(
            Connection connection, long accountId, BankLedgerType type, String reference)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount,balance_after,counterparty_user_id FROM bank_ledger_entries "
                        + "WHERE account_id=? AND entry_type=? AND reference_no=?")) {
            statement.setLong(1, accountId);
            statement.setString(2, type.name());
            statement.setString(3, reference);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ExistingLedger(
                        result.getBigDecimal("amount").setScale(2),
                        result.getBigDecimal("balance_after").setScale(2),
                        nullableLong(result, "counterparty_user_id"));
            }
        }
    }

    private UserIdentity enabledUserByUsername(Connection connection, String username)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,display_name FROM users WHERE username=? AND enabled=TRUE")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new UserIdentity(result.getLong("id"), result.getString("display_name"))
                        : null;
            }
        }
    }

    private String userDisplayName(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT display_name FROM users WHERE id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Transfer sender not found");
                }
                return result.getString(1);
            }
        }
    }

    private long accountId(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM bank_accounts WHERE user_id=?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Bank account not found after creation");
                }
                return result.getLong(1);
            }
        }
    }

    private List<LockedAccount> lockAccounts(
            Connection connection, long firstAccountId, long secondAccountId) throws SQLException {
        long lower = Math.min(firstAccountId, secondAccountId);
        long higher = Math.max(firstAccountId, secondAccountId);
        List<LockedAccount> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,balance,status FROM bank_accounts WHERE id IN (?,?) "
                        + "ORDER BY id FOR UPDATE")) {
            statement.setLong(1, lower);
            statement.setLong(2, higher);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    accounts.add(new LockedAccount(
                            result.getLong("id"),
                            result.getBigDecimal("balance").setScale(2),
                            BankAccountStatus.valueOf(result.getString("status"))));
                }
            }
        }
        if (accounts.size() != 2) {
            throw new SQLException("Transfer accounts not found");
        }
        return accounts;
    }

    private void updateBalance(Connection connection, long accountId, BigDecimal balance)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE bank_accounts SET balance=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            statement.setBigDecimal(1, balance);
            statement.setLong(2, accountId);
            statement.executeUpdate();
        }
    }

    private void insertLedger(
            Connection connection,
            long accountId,
            BankLedgerType type,
            BankLedgerDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String reference,
            Long counterpartyUserId,
            Long operatorUserId,
            String description) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bank_ledger_entries(account_id,entry_type,direction,amount,"
                        + "balance_after,reference_no,counterparty_user_id,operator_user_id,description) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, accountId);
            statement.setString(2, type.name());
            statement.setString(3, direction.name());
            statement.setBigDecimal(4, amount);
            statement.setBigDecimal(5, balanceAfter);
            statement.setString(6, reference);
            statement.setObject(7, counterpartyUserId);
            statement.setObject(8, operatorUserId);
            statement.setString(9, description);
            statement.executeUpdate();
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("金额无效");
        }
        return MoneyPolicy.parsePositive(amount.toPlainString());
    }

    private String requireReference(String reference) {
        String value = reference == null ? "" : reference.trim();
        if (value.isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("业务编号无效");
        }
        return value;
    }

    private void requirePositiveId(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("用户无效");
        }
    }

    private void rollback(Connection connection, Exception original) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
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
        if (query.accountUserId() == null) {
            statement.setNull(1, java.sql.Types.BIGINT);
            statement.setNull(2, java.sql.Types.BIGINT);
        } else {
            statement.setLong(1, query.accountUserId());
            statement.setLong(2, query.accountUserId());
        }
        String type = query.type() == null ? null : query.type().name();
        statement.setString(3, type);
        statement.setString(4, type);
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

    private record LockedAccount(long id, BigDecimal balance, BankAccountStatus status) {
    }

    private record ExistingLedger(
            BigDecimal amount, BigDecimal balanceAfter, Long counterpartyUserId) {
    }

    private record UserIdentity(long userId, String displayName) {
    }
}
