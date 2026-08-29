package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;

import java.sql.SQLException;
import java.util.List;

public interface BankStore {
    BankAccountRecord account(long userId) throws SQLException;

    AccountPage searchAccounts(AccountQuery query) throws SQLException;

    LedgerPage searchLedger(LedgerQuery query) throws SQLException;

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
            long accountUserId, BankLedgerType type, int page, int pageSize) {
        public LedgerQuery {
            if (accountUserId < 1 || page < 1 || pageSize < 1 || pageSize > 100) {
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
}
