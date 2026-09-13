package com.vcampus.server.service;

import com.vcampus.common.model.ForumReportReason;
import com.vcampus.common.model.ForumReportResolution;
import com.vcampus.common.model.ForumReportStatus;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ForumReportStore;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumReportServiceTest {
    private final FakeReportStore store = new FakeReportStore();
    private final SessionManager sessions = new SessionManager();
    private ForumReportService service;
    private RecordingAuditStore audit;
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(11L, "2026000011", "张同学",
                Set.of(UserRole.STUDENT))).token();
        adminToken = sessions.create(account(12L, "T0000012", "李老师",
                Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN))).token();
        audit = new RecordingAuditStore();
        service = new ForumReportService(store, sessions, audit);
    }

    @Test
    void createUsesAuthenticatedReporterAndReturnsId() {
        ResponseMessage response = service.create(request(studentToken, Map.of(
                "targetType", "COMMENT", "targetId", "31", "reason", "SPAM",
                "detail", "重复发布广告", "reporterUserId", "999")));

        assertTrue(response.success());
        assertEquals("81", response.data().get("reportId"));
        assertEquals(11L, store.reporterId);
        assertEquals(ForumTargetType.COMMENT, store.targetType);
        assertEquals(31L, store.targetId);
        assertTrue(audit.entries().isEmpty());
    }

    @Test
    void ordinaryUserCannotSearchOrResolveReports() {
        assertFalse(service.search(request(studentToken, Map.of())).success());
        assertFalse(service.resolve(request(Actions.FORUM_ADMIN_REPORT_RESOLVE, studentToken, Map.of(
                "reportId", "81", "resolution", "DISMISS", "note", "不成立"))).success());
        assertEquals(0, store.resolveCalls);
        assertEquals("DENIED", audit.last().result());
    }

    @Test
    void reportSearchEncodesStableRows() {
        store.page = new ForumReportStore.ReportPage(List.of(new ForumReportStore.ReportRow(
                81L, ForumTargetType.POST, 7L, 7L, "标题\n正文", 11L, "张同学",
                ForumReportReason.MISLEADING, "信息不准确", ForumReportStatus.PENDING,
                null, "", Instant.parse("2026-09-08T02:00:00Z"), null)), 1, 10, 1);

        ResponseMessage response = service.search(request(adminToken, Map.of(
                "status", "PENDING", "page", "1")));

        assertTrue(response.success());
        List<String> fields = RowCodec.decode(response.data().get("row.0"));
        assertEquals(14, fields.size());
        assertEquals(List.of("81", "POST", "7", "7", "标题\n正文"), fields.subList(0, 5));
        assertEquals("MISLEADING", fields.get(7));
    }

    @Test
    void resolutionRequiresANoteAndUsesOperatorSession() {
        ResponseMessage missing = service.resolve(request(
                Actions.FORUM_ADMIN_REPORT_RESOLVE, adminToken, Map.of(
                "reportId", "81", "resolution", "DELETE", "note", " ")));
        assertFalse(missing.success());
        assertEquals("INVALID", audit.last().result());

        ResponseMessage response = service.resolve(request(
                Actions.FORUM_ADMIN_REPORT_RESOLVE, adminToken, Map.of(
                "reportId", "81", "resolution", "DELETE", "note", "确认违反社区规范")));
        assertTrue(response.success());
        assertEquals(ForumReportResolution.DELETE, store.resolution);
        assertEquals(12L, store.operatorId);
        assertEquals("SUCCESS", audit.last().result());
    }

    @Test
    void stateReportsWhetherCurrentUserAlreadyReportedTarget() {
        store.reported = true;
        ResponseMessage response = service.state(request(studentToken, Map.of(
                "targetType", "POST", "targetId", "7")));

        assertTrue(response.success());
        assertEquals("true", response.data().get("reported"));
        assertEquals(11L, store.reporterId);
        assertTrue(audit.entries().isEmpty());
    }

    @Test
    void resolveMapsStoreOutcomesAndSqlFailureToStableAuditResults() {
        store.result = ForumStore.MutationResult.NOT_FOUND;
        assertFalse(resolveAsAdmin().success());
        assertEquals("NOT_FOUND", audit.last().result());

        store.result = ForumStore.MutationResult.UNCHANGED;
        assertFalse(resolveAsAdmin().success());
        assertEquals("CONFLICT", audit.last().result());

        store.result = ForumStore.MutationResult.FORBIDDEN;
        assertFalse(resolveAsAdmin().success());
        assertEquals("DENIED", audit.last().result());

        store.sqlFailure = true;
        assertFalse(resolveAsAdmin().success());
        assertEquals("DATABASE_ERROR", audit.last().result());
    }

    private ResponseMessage resolveAsAdmin() {
        return service.resolve(request(Actions.FORUM_ADMIN_REPORT_RESOLVE, adminToken, Map.of(
                "reportId", "81", "resolution", "DISMISS", "note", "不成立")));
    }

    private RequestMessage request(String token, Map<String, String> values) {
        return request("forum.report.test", token, values);
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, String name, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", name, true, false, roles);
    }

    private static final class FakeReportStore implements ForumReportStore {
        private long reporterId;
        private ForumTargetType targetType;
        private long targetId;
        private boolean reported;
        private int resolveCalls;
        private ForumReportResolution resolution;
        private long operatorId;
        private ReportPage page = new ReportPage(List.of(), 1, 10, 0);
        private ForumStore.MutationResult result = ForumStore.MutationResult.CHANGED;
        private boolean sqlFailure;

        @Override
        public long create(long reporterId, ForumTargetType targetType, long targetId,
                           ForumReportReason reason, String detail) {
            this.reporterId = reporterId;
            this.targetType = targetType;
            this.targetId = targetId;
            return 81L;
        }

        @Override
        public boolean reportedBy(long userId, ForumTargetType targetType, long targetId) {
            reporterId = userId;
            this.targetType = targetType;
            this.targetId = targetId;
            return reported;
        }

        @Override public ReportPage search(ReportQuery query) { return page; }

        @Override
        public ForumStore.MutationResult resolve(long reportId, ForumReportResolution resolution,
                                                 String note, long operatorId) throws SQLException {
            if (sqlFailure) throw new SQLException("offline");
            resolveCalls++;
            this.resolution = resolution;
            this.operatorId = operatorId;
            return result;
        }
    }
}
