package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Performs dashboard protocol calls synchronously; JavaFX callers run it off the UI thread. */
public final class CampusDashboardLoader {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String UNAVAILABLE = "不可用";

    private final VCampusClient client;
    private final String token;
    private final Set<UserRole> roles;
    private final String displayName;

    public CampusDashboardLoader(VCampusClient client, String token, Set<UserRole> roles, String displayName) {
        this.client = Objects.requireNonNull(client, "client");
        this.token = Objects.requireNonNull(token, "token");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        RoleCompositionPolicy.requireValid(this.roles);
        this.displayName = Objects.requireNonNullElse(displayName, "");
    }

    public CampusDashboardData load(LocalDate date) {
        checkCancelled();
        Objects.requireNonNull(date, "date");
        if (roles.contains(UserRole.SUPER_ADMIN)) {
            return managementDashboard(date);
        }
        verifySession();

        List<String> notices = new ArrayList<>();
        List<CampusDashboardData.Course> courses = new ArrayList<>();
        List<CampusDashboardData.Task> tasks = new ArrayList<>();
        Profile profile = loadProfile(notices);
        Term term = loadTerm(date, notices);
        if (term != null) loadCourses(term, date, courses, notices);
        String loans = loadLoans(tasks, notices);
        OrderSummary orders = loadOrders(tasks, notices);
        String balance = loadBalance(notices);
        checkCancelled();
        return new CampusDashboardData(profile.name(), profile.identity(), profile.line(), date,
                courses, tasks, loans, orders.count(), balance, notices);
    }

    private CampusDashboardData managementDashboard(LocalDate date) {
        return new CampusDashboardData(displayName, "超级管理员", "管理账户 · 校园业务入口", date,
                List.of(), List.of(), "不适用", "不适用", "不适用",
                List.of("当前为系统管理员视图，请通过工作台进入管理功能。"));
    }

    private void verifySession() {
        try {
            ResponseMessage response = client.currentSession(token);
            requireSuccess(response, "会话");
            if (Boolean.parseBoolean(response.data().getOrDefault("forcePasswordChange", "false"))) {
                throw new IllegalStateException("请先修改密码后再进入校园主页");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法验证登录会话", exception);
        }
    }

    private Profile loadProfile(List<String> notices) {
        checkCancelled();
        try {
            ResponseMessage response;
            if (roles.contains(UserRole.STUDENT)) {
                response = client.getMyStudentProfile(token);
                requireSuccess(response, "学生档案");
                Map<String, String> data = response.data();
                String name = nonBlank(data.get("fullName"), displayName);
                return new Profile(name, "学生", joinNonBlank(" · ", data.get("studentNumber"),
                        data.get("departmentName"), data.get("majorName"), data.get("className")));
            }
            if (roles.contains(UserRole.TEACHER)) {
                response = client.getMyTeacherProfile(token);
                requireSuccess(response, "教师档案");
                Map<String, String> data = response.data();
                String name = nonBlank(data.get("fullName"), displayName);
                return new Profile(name, "教师", joinNonBlank(" · ", data.get("teacherNumber"),
                        data.get("departmentName"), data.get("professionalTitle")));
            }
        } catch (IOException | RuntimeException exception) {
            notices.add("个人档案不可用：" + message(exception));
        }
        return new Profile(displayName, "校园用户", "个人档案不可用");
    }

    private Term loadTerm(LocalDate date, List<String> notices) {
        checkCancelled();
        try {
            ResponseMessage response = client.academicReferenceData(token);
            requireSuccess(response, "教务基础数据");
            Map<String, String> data = response.data();
            int count = parseNonNegative(data.get("term.count"));
            boolean dateMetadataPresent = false;
            for (int index = 0; index < count; index++) {
                String prefix = "term." + index;
                List<String> row = com.vcampus.common.protocol.RowCodec.decode(required(data, prefix));
                if (row.size() != 3) throw new IllegalArgumentException("学期数据格式不正确");
                String startText = data.get(prefix + ".startDate");
                String endText = data.get(prefix + ".endDate");
                if (startText == null || endText == null) {
                    continue;
                }
                dateMetadataPresent = true;
                LocalDate start = LocalDate.parse(startText);
                LocalDate end = LocalDate.parse(endText);
                if (!end.isBefore(start) && !date.isBefore(start) && !date.isAfter(end)) {
                    return new Term(Long.parseLong(row.getFirst()), start, end);
                }
            }
            if (count > 0 && !dateMetadataPresent) {
                notices.add("服务器未提供可用的学期日期，无法确定当日课程。");
            }
            return null;
        } catch (IOException | RuntimeException exception) {
            notices.add("课程不可用：" + message(exception));
            return null;
        }
    }

    private void loadCourses(Term term, LocalDate date, List<CampusDashboardData.Course> courses,
                             List<String> notices) {
        checkCancelled();
        try {
            ResponseMessage response = roles.contains(UserRole.STUDENT)
                    ? client.mySchedule(token, term.id())
                    : client.teacherSchedule(token, term.id(), null);
            requireSuccess(response, "课表");
            AcademicViewData.schedules(response).stream()
                    .filter(row -> row.dayOfWeek() == date.getDayOfWeek().getValue()
                            && withinWeek(row, term.start(), date))
                    .sorted(Comparator.comparingInt(AcademicViewData.ScheduleEntryView::startPeriod)
                            .thenComparingInt(AcademicViewData.ScheduleEntryView::endPeriod))
                    .forEach(row -> courses.add(new CampusDashboardData.Course(
                            row.courseName(), row.classroom(),
                            "第" + row.startPeriod() + "—" + row.endPeriod() + "节",
                            joinNonBlank(" · ", row.courseCode(), row.sectionCode(), row.teacherName()))));
        } catch (IOException | RuntimeException exception) {
            notices.add("课表不可用：" + message(exception));
        }
    }

    private boolean withinWeek(AcademicViewData.ScheduleEntryView row, LocalDate termStart, LocalDate date) {
        LocalDate firstMonday = termStart.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        long week = ChronoUnit.WEEKS.between(firstMonday, date) + 1;
        return week >= row.startWeek() && week <= row.endWeek();
    }

    private String loadLoans(List<CampusDashboardData.Task> tasks, List<String> notices) {
        checkCancelled();
        try {
            LibraryViewData.LoanPage first = LibraryViewData.loanPage(client.myLibraryLoans(token, "active", 1));
            List<LibraryViewData.LoanRow> rows = new ArrayList<>(first.rows());
            int pageCount = (first.total() + first.pageSize() - 1) / first.pageSize();
            for (int page = 2; page <= pageCount; page++) {
                checkCancelled();
                LibraryViewData.LoanPage nextPage = LibraryViewData.loanPage(
                        client.myLibraryLoans(token, "active", page));
                List<LibraryViewData.LoanRow> next = nextPage.rows();
                if (nextPage.total() != first.total()) {
                    notices.add("借阅数据在加载过程中发生变化，已显示当前可用记录。");
                }
                if (next.isEmpty()) {
                    if (nextPage.total() == first.total()) {
                        notices.add("借阅数据在加载过程中发生变化，已显示当前可用记录。");
                    }
                    break;
                }
                rows.addAll(next);
            }
            LocalDate today = LocalDate.now(CAMPUS_ZONE);
            for (LibraryViewData.LoanRow row : rows) {
                LocalDate due = row.dueAt().atZone(CAMPUS_ZONE).toLocalDate();
                if (!due.isAfter(today.plusDays(3))) {
                    tasks.add(new CampusDashboardData.Task(row.title(), "应还：" + due,
                            "library-loans", !due.isAfter(today)));
                }
            }
            return Integer.toString(first.total());
        } catch (IOException | RuntimeException exception) {
            checkCancelled();
            notices.add("借阅不可用：" + message(exception));
            return UNAVAILABLE;
        }
    }

    private OrderSummary loadOrders(List<CampusDashboardData.Task> tasks, List<String> notices) {
        checkCancelled();
        try {
            ShopViewData.OrderPage paid = ShopViewData.orderPage(
                    client.searchShopOrders(token, ShopOrderStatus.PAID.name(), 1));
            checkCancelled();
            ShopViewData.OrderPage shipped = ShopViewData.orderPage(
                    client.searchShopOrders(token, ShopOrderStatus.SHIPPED.name(), 1));
            for (ShopViewData.OrderRow row : shipped.rows().stream().limit(5).toList()) {
                tasks.add(new CampusDashboardData.Task("订单待收货：" + row.orderNo(),
                        "下单于 " + row.createdAt().atZone(CAMPUS_ZONE).toLocalDate(), "shop-orders", false));
            }
            return new OrderSummary(Integer.toString(paid.total() + shipped.total()));
        } catch (IOException | RuntimeException exception) {
            checkCancelled();
            notices.add("订单不可用：" + message(exception));
            return new OrderSummary(UNAVAILABLE);
        }
    }

    private String loadBalance(List<String> notices) {
        checkCancelled();
        try {
            ResponseMessage response = client.getBankAccountSummary(token);
            requireSuccess(response, "余额");
            String opened = response.data().get("opened");
            if (!"true".equals(opened) && !"false".equals(opened)) {
                throw new IllegalArgumentException("余额状态格式不正确");
            }
            if (!Boolean.parseBoolean(opened)) {
                return "未开户";
            }
            BankViewData.AccountView account = BankViewData.account(response);
            return "¥" + account.balance().setScale(2).toPlainString();
        } catch (IOException | RuntimeException exception) {
            notices.add("余额不可用：" + message(exception));
            return UNAVAILABLE;
        }
    }

    private void requireSuccess(ResponseMessage response, String label) {
        if (response == null || !response.success()) {
            throw new IllegalStateException(label + "失败：" + (response == null ? "服务器无响应" : response.message()));
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("校园查询已取消");
    }

    private int parseNonNegative(String text) {
        int value = Integer.parseInt(text);
        if (value < 0) throw new IllegalArgumentException("数量无效");
        return value;
    }

    private String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException("缺少 " + key);
        return value;
    }

    private String message(Exception exception) {
        return nonBlank(exception.getMessage(), "服务器数据格式不正确");
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String joinNonBlank(String delimiter, String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(delimiter));
    }

    private record Profile(String name, String identity, String line) { }
    private record Term(long id, LocalDate start, LocalDate end) { }
    private record OrderSummary(String count) { }
}
