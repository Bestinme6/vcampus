package com.vcampus.server.service;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.AuditQueryStore;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.database.SystemManagementStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemManagementServiceTest {
    private FakeSystemStore configurationStore;
    private FakeAuditQueryStore queryStore;
    private FakeAuditStore audit;
    private SystemManagementService service;
    private String superAdminToken;
    private String teacherToken;

    @BeforeEach
    void setUp() {
        configurationStore = new FakeSystemStore();
        queryStore = new FakeAuditQueryStore();
        audit = new FakeAuditStore();
        SessionManager sessions = new SessionManager();
        superAdminToken = sessions.create(account(1, "admin", Set.of(UserRole.SUPER_ADMIN))).token();
        teacherToken = sessions.create(account(2, "teacher", Set.of(UserRole.TEACHER))).token();
        service = new SystemManagementService(configurationStore, queryStore, audit, sessions);
    }

    @Test
    void onlySuperAdministratorCanReadConfiguration() {
        assertTrue(service.getConfig(request(Actions.SYSTEM_CONFIG_GET, superAdminToken, Map.of())).success());
        assertEquals("没有系统管理权限",
                service.getConfig(request(Actions.SYSTEM_CONFIG_GET, teacherToken, Map.of())).message());
        assertEquals("会话已失效，请重新登录",
                service.getConfig(request(Actions.SYSTEM_CONFIG_GET, "missing", Map.of())).message());
        assertEquals(1, configurationStore.configurationCalls);
    }

    @Test
    void updatePeriodsRejectsOverlapBeforeCallingStore() {
        Map<String, String> values = periods();
        values.put("period.0", new ClassPeriodTime(1, LocalTime.parse("08:00"), LocalTime.parse("09:00")).encode());
        values.put("period.1", new ClassPeriodTime(2, LocalTime.parse("08:45"), LocalTime.parse("09:30")).encode());

        ResponseMessage response = service.updatePeriods(
                request(Actions.SYSTEM_CONFIG_UPDATE_PERIODS, superAdminToken, values), "127.0.0.1");

        assertFalse(response.success());
        assertEquals("相邻节次时间不能重叠", response.message());
        assertEquals(0, configurationStore.updatePeriodCalls);
    }

    @Test
    void successfulTermUpdateRecordsExactAuditAction() {
        ResponseMessage response = service.updateTerm(request(Actions.SYSTEM_CONFIG_UPDATE_TERM,
                superAdminToken, Map.of("term", term().encode())), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(Actions.SYSTEM_CONFIG_UPDATE_TERM, audit.action);
        assertEquals("SUCCESS", audit.result);
        assertEquals("127.0.0.1", audit.address);
    }

    @Test
    void auditQueryUsesFixedPageSizeAndRejectsReverseDates() {
        ResponseMessage ok = service.searchAudit(request(Actions.SYSTEM_AUDIT_SEARCH, superAdminToken,
                Map.of("page", "3", "fromDate", "2026-09-01", "toDate", "2026-09-12")));
        assertTrue(ok.success());
        assertEquals(3, queryStore.query.page());
        assertEquals(20, queryStore.query.pageSize());

        ResponseMessage invalid = service.searchAudit(request(Actions.SYSTEM_AUDIT_SEARCH, superAdminToken,
                Map.of("fromDate", "2026-09-12", "toDate", "2026-09-01")));
        assertFalse(invalid.success());
    }

    private Map<String, String> periods() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("period.count", "12");
        for (int index = 0; index < 12; index++) {
            values.put("period." + index, new ClassPeriodTime(index + 1, null, null).encode());
        }
        return values;
    }

    private SystemTermConfig term() {
        return new SystemTermConfig(1, "2026-2027", 1, "第一学期",
                LocalDate.parse("2026-09-01"), LocalDate.parse("2027-01-15"),
                LocalDateTime.parse("2026-08-20T00:00:00"),
                LocalDateTime.parse("2026-09-10T23:59:59"),
                LocalDateTime.parse("2026-09-20T23:59:59"), AcademicTermStatus.SELECTION);
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", username, true, false, roles);
    }

    private final class FakeSystemStore implements SystemManagementStore {
        int configurationCalls;
        int updatePeriodCalls;

        @Override
        public Snapshot configuration() {
            configurationCalls++;
            return new Snapshot(List.of(term()), java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(number -> new ClassPeriodTime(number, null, null)).toList());
        }

        @Override
        public boolean updateTerm(SystemTermConfig term, long operatorUserId) {
            return true;
        }

        @Override
        public void updatePeriods(List<ClassPeriodTime> periods, long operatorUserId) {
            updatePeriodCalls++;
        }
    }

    private static final class FakeAuditQueryStore implements AuditQueryStore {
        AuditQuery query;

        @Override
        public AuditPage search(AuditQuery query) {
            this.query = query;
            return new AuditPage(List.of(), query.page(), query.pageSize(), 0);
        }
    }

    private static final class FakeAuditStore implements AuditStore {
        String action;
        String result;
        String address;

        @Override
        public void record(Long userId, String action, String result, String clientAddress) {
            this.action = action;
            this.result = result;
            this.address = clientAddress;
        }
    }
}
