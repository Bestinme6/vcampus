package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusDashboardLoaderTest {
    @Test
    void studentOverviewFiltersSchedulesByContainingMondayAndSelectedDay() throws Exception {
        List<RequestMessage> requests = Collections.synchronizedList(new ArrayList<>());
        try (StubServer server = new StubServer(requests,
                success(Map.of("forcePasswordChange", "false")),
                success(Map.of("fullName", "林同学", "studentNumber", "20260001",
                        "departmentName", "计算机学院", "majorName", "软件工程", "className", "软件 1 班")),
                success(Map.of("term.count", "1", "term.0", RowCodec.encode("1", "2026 秋", "IN_PROGRESS"),
                        "term.0.startDate", "2026-08-31", "term.0.endDate", "2027-01-17",
                        "course.count", "0", "teacher.count", "0")),
                success(scheduleData(
                        schedule("Period ten", 1, 10, 10, 1, 1, "九龙湖 A110"),
                        schedule("Java", 1, 3, 4, 1, 1, "九龙湖 A101"),
                        schedule("Early", 1, 2, 2, 1, 1, "九龙湖 A100"),
                        schedule("Tuesday", 2, 1, 2, 1, 1, "九龙湖 A102"),
                        schedule("Week two", 1, 5, 6, 2, 2, "九龙湖 A103"))),
                success(emptyLoans()),
                success(emptyOrders()),
                success(emptyOrders()),
                success(Map.of("accountId", "1", "username", "lin", "displayName", "林同学",
                        "balance", "88.00", "status", "ACTIVE", "updatedAt", "2026-08-31T00:00:00Z",
                        "opened", "true")))) {
            CampusDashboardData data = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.STUDENT), "登录名")
                    .load(LocalDate.of(2026, 8, 31));

            assertEquals("林同学", data.displayName());
            assertEquals("学生", data.identity());
            assertEquals(List.of("Early", "Java", "Period ten"),
                    data.courses().stream().map(CampusDashboardData.Course::title).toList());
            assertEquals(List.of("第2—2节", "第3—4节", "第10—10节"),
                    data.courses().stream().map(CampusDashboardData.Course::periods).toList());
            assertEquals("0", data.loanCount());
            assertEquals("0", data.orderCount());
            assertEquals("¥88.00", data.balance());
            assertEquals(List.of("auth.session", "student.getSelf", "academic.referenceData",
                    "academic.schedule.my", "library.loan.my", "shop.order.search", "shop.order.search",
                    "bank.account.summary"), requests.stream().map(RequestMessage::action).toList());
        }
    }

    @Test
    void selectedSecondMondayIncludesSecondWeekCourseOnly() throws Exception {
        try (StubServer server = new StubServer(new ArrayList<>(),
                success(Map.of("forcePasswordChange", "false")),
                success(Map.of("fullName", "林同学", "studentNumber", "20260001")),
                success(Map.of("term.count", "1", "term.0", RowCodec.encode("1", "2026 秋", "IN_PROGRESS"),
                        "term.0.startDate", "2026-08-31", "term.0.endDate", "2027-01-17",
                        "course.count", "0", "teacher.count", "0")),
                success(scheduleData(schedule("Week two", 1, 5, 6, 2, 2, "九龙湖 A103"))),
                success(emptyLoans()), success(emptyOrders()), success(emptyOrders()),
                success(Map.of("accountId", "1", "username", "lin", "displayName", "林同学",
                        "balance", "0.00", "status", "ACTIVE", "updatedAt", "2026-08-31T00:00:00Z",
                        "opened", "true")))) {
            CampusDashboardData data = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.STUDENT), "登录名")
                    .load(LocalDate.of(2026, 9, 7));
            assertEquals(List.of("Week two"), data.courses().stream().map(CampusDashboardData.Course::title).toList());
        }
    }

    @Test
    void forcedPasswordChangeFailsInsteadOfRenderingAnEmptyDashboard() throws Exception {
        try (StubServer server = new StubServer(new ArrayList<>(),
                success(Map.of("forcePasswordChange", "true")))) {
            CampusDashboardLoader loader = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.STUDENT), "登录名");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> loader.load(LocalDate.of(2026, 8, 31)));
            assertTrue(failure.getMessage().contains("修改密码"));
        }
    }

    @Test
    void expiredSessionFailsBeforeAnyBusinessRequest() throws Exception {
        List<RequestMessage> requests = Collections.synchronizedList(new ArrayList<>());
        try (StubServer server = new StubServer(requests, ResponseMessage.failure("response", "登录已过期，请重新登录"))) {
            CampusDashboardLoader loader = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.STUDENT), "登录名");
            assertThrows(IllegalStateException.class, () -> loader.load(LocalDate.of(2026, 8, 31)));
            assertEquals(List.of("auth.session"), requests.stream().map(RequestMessage::action).toList());
        }
    }

    @Test
    void malformedScheduleIsReportedWithoutReplacingOtherCardsWithZeroes() throws Exception {
        try (StubServer server = new StubServer(new ArrayList<>(),
                success(Map.of("forcePasswordChange", "false")),
                success(Map.of("fullName", "林同学", "studentNumber", "20260001")),
                success(Map.of("term.count", "1", "term.0", RowCodec.encode("1", "2026 秋", "IN_PROGRESS"),
                        "term.0.startDate", "2026-08-31", "term.0.endDate", "2027-01-17",
                        "course.count", "0", "teacher.count", "0")),
                success(Map.of("count", "1", "row.0", RowCodec.encode("malformed"))),
                success(emptyLoans()), success(emptyOrders()), success(emptyOrders()),
                success(Map.of("opened", "false")))) {
            CampusDashboardData data = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.STUDENT), "登录名")
                    .load(LocalDate.of(2026, 8, 31));
            assertTrue(data.notices().stream().anyMatch(message -> message.startsWith("课表不可用")));
            assertEquals("0", data.loanCount());
            assertEquals("0", data.orderCount());
            assertEquals("未开户", data.balance());
        }
    }

    @Test
    void superAdministratorAvoidsAllPersonalRequests() throws Exception {
        List<RequestMessage> requests = Collections.synchronizedList(new ArrayList<>());
        try (StubServer server = new StubServer(requests)) {
            CampusDashboardData data = new CampusDashboardLoader(
                    new VCampusClient("127.0.0.1", server.port()), "token", Set.of(UserRole.SUPER_ADMIN), "管理员")
                    .load(LocalDate.of(2026, 8, 31));
            assertEquals("超级管理员", data.identity());
            assertTrue(data.notices().getFirst().contains("管理"));
            assertTrue(requests.isEmpty());
        }
    }

    private static Map<String, String> scheduleData(String... rows) {
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("count", Integer.toString(rows.length));
        for (int index = 0; index < rows.length; index++) data.put("row." + index, rows[index]);
        return data;
    }

    @Test
    void missingTermDatesDoesNotGuessTheCurrentTerm() throws Exception {
        checkNoScheduleFor(Map.of("term.count","1","term.0",RowCodec.encode("1","2026 秋","IN_PROGRESS")),
                LocalDate.of(2026,8,31), true);
    }

    @Test
    void datesOutsideTermDoNotRequestStudentSchedule() throws Exception {
        Map<String,String> term=Map.of("term.count","1","term.0",RowCodec.encode("1","2026 秋","IN_PROGRESS"),
                "term.0.startDate","2026-08-31","term.0.endDate","2027-01-17");
        checkNoScheduleFor(term,LocalDate.of(2026,8,30),false);
        checkNoScheduleFor(term,LocalDate.of(2027,1,18),false);
    }

    private void checkNoScheduleFor(Map<String,String> term,LocalDate date,boolean missingDates) throws Exception {
        List<RequestMessage> requests=Collections.synchronizedList(new ArrayList<>());
        try(StubServer server=new StubServer(requests,success(Map.of("forcePasswordChange","false")),
                success(Map.of("fullName","演示学生")),success(term),success(emptyLoans()),
                success(emptyOrders()),success(emptyOrders()),success(Map.of("opened","false")))) {
            CampusDashboardData data=new CampusDashboardLoader(new VCampusClient("127.0.0.1",server.port()),
                    "token",Set.of(UserRole.STUDENT),"演示学生").load(date);
            assertTrue(data.courses().isEmpty());
            assertTrue(requests.stream().noneMatch(r->r.action().equals("academic.schedule.my")));
            assertEquals(missingDates,data.notices().stream().anyMatch(n->n.contains("学期日期")));
        }
    }

    private static String schedule(String title, int day, int start, int end, int fromWeek, int toWeek, String room) {
        return RowCodec.encode("1", "1", "2026 秋", "C001", title, "01", "王老师",
                Integer.toString(day), Integer.toString(start), Integer.toString(end),
                Integer.toString(fromWeek), Integer.toString(toWeek), room);
    }

    private static Map<String, String> emptyLoans() {
        return Map.of("count", "0", "page", "1", "pageSize", "10", "total", "0");
    }

    private static Map<String, String> emptyOrders() {
        return Map.of("count", "0", "page", "1", "pageSize", "8", "total", "0");
    }

    private static ResponseMessage success(Map<String, String> data) {
        return ResponseMessage.success("response", "ok", data);
    }

    private static final class StubServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;

        private StubServer(List<RequestMessage> requests, ResponseMessage... responses) throws Exception {
            server = new ServerSocket(0);
            thread = Thread.ofVirtual().start(() -> {
                try {
                    for (ResponseMessage response : responses) {
                        try (Socket socket = server.accept();
                             var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                             var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                            RequestMessage request = MessageCodec.readRequest(input);
                            requests.add(request);
                            MessageCodec.writeResponse(output, new ResponseMessage(
                                    request.requestId(), response.success(), response.message(), response.data()));
                        }
                    }
                } catch (Exception ignored) {
                    // Closing the server ends a deliberately incomplete scripted exchange.
                }
            });
        }

        private int port() { return server.getLocalPort(); }

        @Override public void close() throws Exception {
            server.close();
            thread.join(1_000);
        }
    }
}
