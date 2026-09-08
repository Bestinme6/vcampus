package com.vcampus.server.database;

import com.vcampus.common.model.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public interface ForumReportStore {
    long create(long reporterId, ForumTargetType targetType, long targetId,
                ForumReportReason reason, String detail) throws SQLException;
    boolean reportedBy(long userId, ForumTargetType targetType, long targetId) throws SQLException;
    ReportPage search(ReportQuery query) throws SQLException;
    ForumStore.MutationResult resolve(long reportId, ForumReportResolution resolution,
                                      String note, long operatorId) throws SQLException;

    record ReportQuery(ForumReportStatus status, ForumTargetType targetType,
                       ForumReportReason reason, int page, int pageSize) { }
    record ReportRow(long id, ForumTargetType targetType, long targetId, long postId,
                     String content, long reporterUserId, String reporterDisplayName,
                     ForumReportReason reason, String detail, ForumReportStatus status,
                     ForumReportResolution resolution, String handlerDisplayName,
                     Instant createdAt, Instant handledAt) { }
    record ReportPage(List<ReportRow> rows, int page, int pageSize, int total) {
        public ReportPage { rows = List.copyOf(rows); }
    }
}
