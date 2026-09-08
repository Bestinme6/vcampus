package com.vcampus.server.service;

import com.vcampus.common.model.ForumReportReason;
import com.vcampus.common.model.ForumReportResolution;
import com.vcampus.common.model.ForumReportStatus;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ForumReportStore;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(11L, "2026000011", "张同学",
                Set.of(UserRole.STUDENT))).token();
        adminToken = sessions.create(account(12L, "T0000012", "李老师",
                Set.of(UserRole.TEACHER, UserRole.FORUM_ADMIN))).token();
        service = new ForumReportService(store, sessions);
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
    }

    @Test
    void ordinaryUserCannotSearchOrResolveReports() {
        assertFalse(service.search(request(studentToken, Map.of())).success());
        assertFalse(service.resolve(request(studentToken, Map.of(
                "reportId", "81", "resolution", "DISMISS", "note", "不成立"))).success());
        assertEquals(0, store.resolveCalls);
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
        ResponseMessage missing = service.resolve(request(adminToken, Map.of(
                "reportId", "81", "resolution", "DELETE", "note", " ")));
        assertFalse(missing.success());

        ResponseMessage response = service.resolve(request(adminToken, Map.of(
                "reportId", "81", "resolution", "DELETE", "note", "确认违反社区规范")));
        assertTrue(response.success());
        assertEquals(ForumReportResolution.DELETE, store.resolution);
        assertEquals(12L, store.operatorId);
    }

    @Test
    void stateReportsWhetherCurrentUserAlreadyReportedTarget() {
        store.reported = true;
        ResponseMessage response = service.state(request(studentToken, Map.of(
                "targetType", "POST", "targetId", "7")));

        assertTrue(response.success());
        assertEquals("true", response.data().get("reported"));
        assertEquals(11L, store.reporterId);
    }

    private RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create("forum.report.test", parameters);
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
                                                 String note, long operatorId) {
            resolveCalls++;
            this.resolution = resolution;
            this.operatorId = operatorId;
            return ForumStore.MutationResult.CHANGED;
        }
    }
}
