package com.vcampus.server.database;

import com.vcampus.common.model.AuditLogEntry;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

public interface AuditQueryStore {
    record AuditQuery(String userKeyword, String actionPrefix, String resultCode,
                      LocalDate fromDate, LocalDate toDate, int page, int pageSize) {
        public AuditQuery {
            userKeyword = normalize(userKeyword);
            actionPrefix = normalize(actionPrefix);
            resultCode = normalize(resultCode);
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                throw new IllegalArgumentException("开始日期不能晚于结束日期");
            }
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("分页参数无效");
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    record AuditPage(List<AuditLogEntry> rows, int page, int pageSize, int total) {
        public AuditPage {
            rows = List.copyOf(rows);
        }
    }

    AuditPage search(AuditQuery query) throws SQLException;
}
