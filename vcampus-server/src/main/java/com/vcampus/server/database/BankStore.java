package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;

import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BankStore {
    default Recipient recipient(String username) throws SQLException {
        throw new BankRuleException("暂不支持收款人核对");
    }

    default long ledgerOrder(long userId, String referenceNo) throws SQLException {
        throw new BankRuleException("未找到本人关联订单");
    }

    record Recipient(long userId, String username, String displayName) { }
    BankAccountRecord account(long userId) throws SQLException;

    Optional<BankAccountRecord> accountSummary(long userId) throws SQLException;

    AccountPage searchAccounts(AccountQuery query) throws SQLException;

    LedgerPage searchLedger(LedgerQuery query) throws SQLException;

    TopUpResult topUp(
            long operatorUserId, String targetUsername, BigDecimal amount, String operationId)
            throws SQLException;

    StatusResult setStatus(
            long operatorUserId, String targetUsername, BankAccountStatus status)
            throws SQLException;

    TransferResult transfer(
            long senderUserId, String recipientUsername, BigDecimal amount, String operationId)
            throws SQLException;

    record AccountQuery(
            String keyword, BankAccountStatus status, int page, int pageSize) {
        public AccountQuery {
            keyword = keyword == null ? "" : keyword.trim();
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("分页参数无效");
            }
        }
    }

    record LedgerQuery(
            String accountUsername, BankLedgerType type, int page, int pageSize,
            String keyword, java.time.Instant from, java.time.Instant until, String referenceNo) {
        public LedgerQuery(String accountUsername, BankLedgerType type, int page, int pageSize) {
            this(accountUsername, type, page, pageSize, "", null, null, "");
        }
        public LedgerQuery {
            keyword = keyword == null ? "" : keyword.trim();
            referenceNo = referenceNo == null ? "" : referenceNo.trim();
            if (keyword.length() > 100 || referenceNo.length() > 64
                    || from != null && until != null && !from.isBefore(until)) {
                throw new IllegalArgumentException("流水筛选条件无效");
            }
            accountUsername = accountUsername == null || accountUsername.isBlank()
                    ? null : accountUsername.trim();
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("流水查询参数无效");
            }
        }
    }

    record AccountPage(List<BankAccountRecord> rows, int page, int pageSize, int total) {
        public AccountPage {
            rows = List.copyOf(rows);
        }
    }

    record LedgerPage(List<BankLedgerRecord> rows, int page, int pageSize, int total,
                      BigDecimal income, BigDecimal expense) {
        public LedgerPage(List<BankLedgerRecord> rows, int page, int pageSize, int total) {
            this(rows, page, pageSize, total, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        public LedgerPage {
            rows = List.copyOf(rows);
        }
    }

    record TopUpResult(
            long accountId, BigDecimal balanceAfter, String referenceNo, boolean duplicate) {
    }

    record StatusResult(long accountId, BankAccountStatus status, boolean changed) {
    }

    record TransferResult(
            String referenceNo,
            BigDecimal senderBalanceAfter,
            BigDecimal recipientBalanceAfter,
            boolean duplicate) {
    }
}
