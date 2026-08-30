package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;

import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.List;

public interface BankStore {
    BankAccountRecord account(long userId) throws SQLException;

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
            String accountUsername, BankLedgerType type, int page, int pageSize) {
        public LedgerQuery {
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

    record LedgerPage(List<BankLedgerRecord> rows, int page, int pageSize, int total) {
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
