package com.vcampus.server.service;

import com.vcampus.common.model.AccountAccessPolicy;
import com.vcampus.common.model.Gender;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.AccountStore;
import com.vcampus.server.database.AccountStore.AccountPage;
import com.vcampus.server.database.AccountStore.AccountReferences;
import com.vcampus.server.database.AccountStore.CreateStudentAccount;
import com.vcampus.server.database.AccountStore.CreateTeacherAccount;
import com.vcampus.server.database.AccountStore.ReferenceItem;
import com.vcampus.server.database.AccountStore.MutationResult;
import com.vcampus.server.database.AuditStore;
import com.vcampus.server.model.AccountSummary;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class AccountService {
    private static final int PAGE_SIZE = 8;

    private final AccountStore accounts;
    private final AuditStore audit;
    private final PasswordHasher passwordHasher;
    private final SessionManager sessions;

    public AccountService(
            AccountStore accounts,
            AuditStore audit,
            PasswordHasher passwordHasher,
            SessionManager sessions) {
        this.accounts = accounts;
        this.audit = audit;
        this.passwordHasher = passwordHasher;
        this.sessions = sessions;
    }

    public ResponseMessage search(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            int page = parseInteger(request.parameters().getOrDefault("page", "1"), "页码", 1, 100_000);
            UserRole identity = parseOptionalIdentity(request.parameters().get("identity"));
            Boolean enabled = parseOptionalBoolean(request.parameters().get("enabled"), "启用状态");
            AccountPage result = accounts.search(
                    request.parameters().getOrDefault("keyword", ""), identity, enabled,
                    page, PAGE_SIZE);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("page", Integer.toString(result.page()));
            data.put("pageSize", Integer.toString(result.pageSize()));
            data.put("total", Integer.toString(result.total()));
            data.put("count", Integer.toString(result.rows().size()));
            for (int index = 0; index < result.rows().size(); index++) {
                data.put("row." + index, encode(result.rows().get(index)));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage referenceData(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            AccountReferences references = accounts.referenceData();
            Map<String, String> data = new LinkedHashMap<>();
            putReferences(data, "department", references.departments());
            putReferences(data, "major", references.majors());
            putReferences(data, "class", references.classes());
            return ResponseMessage.success(request.requestId(), "基础数据加载成功", data);
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage create(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        char[] password = request.parameters().getOrDefault("initialPassword", "").toCharArray();
        try {
            validatePassword(password, "临时密码");
            UserRole identity = parseRequiredIdentity(request.parameters().get("identity"));
            Set<UserRole> roles = completeRoles(identity, request.parameters().get("roles"));
            PasswordHasher.PasswordHash passwordHash = passwordHasher.hash(password);
            long profileId;
            String username;
            if (identity == UserRole.STUDENT) {
                CreateStudentAccount command = parseStudent(request.parameters(), roles);
                username = command.studentNumber();
                profileId = accounts.createStudent(command, passwordHash, session.get().userId());
            } else {
                CreateTeacherAccount command = parseTeacher(request.parameters(), roles);
                username = command.teacherNumber();
                profileId = accounts.createTeacher(command, passwordHash);
            }
            audit.record(session.get().userId(), Actions.ACCOUNT_CREATE, "SUCCESS", null);
            return ResponseMessage.success(
                    request.requestId(), "账号创建成功",
                    Map.of("profileId", Long.toString(profileId), "username", username));
        } catch (SQLIntegrityConstraintViolationException exception) {
            audit.record(session.get().userId(), Actions.ACCOUNT_CREATE, "DUPLICATE", null);
            return ResponseMessage.failure(request.requestId(), "登录账号、学号或教师工号已经存在");
        } catch (IllegalArgumentException exception) {
            audit.record(session.get().userId(), Actions.ACCOUNT_CREATE, "INVALID", null);
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            audit.record(session.get().userId(), Actions.ACCOUNT_CREATE, "DATABASE_ERROR", null);
            return databaseFailure(request, exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public ResponseMessage updateRoles(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            long userId = parseLong(request.parameters().get("userId"), "账号ID");
            AccountSummary target = accounts.findManageableById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("账号不存在或不可管理"));
            Set<UserRole> complete = completeRoles(
                    target.baseIdentity(), request.parameters().get("roles"));
            Set<UserRole> administrative = RoleCompositionPolicy.administrativeRoles(complete);
            MutationResult result = accounts.replaceAdministrativeRoles(
                    userId, target.baseIdentity(), administrative,
                    session.get().userId(), session.get().displayName());
            if (result == MutationResult.NOT_FOUND) {
                return ResponseMessage.failure(request.requestId(), "账号不存在或不可管理");
            }
            if (result == MutationResult.CHANGED) {
                sessions.invalidateUser(userId);
            }
            audit.record(session.get().userId(), Actions.ACCOUNT_UPDATE_ROLES, "SUCCESS", null);
            return ResponseMessage.success(request.requestId(), "管理员角色已更新", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage setEnabled(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        try {
            long userId = parseLong(request.parameters().get("userId"), "账号ID");
            boolean enabled = parseRequiredBoolean(request.parameters().get("enabled"), "启用状态");
            MutationResult result = accounts.setEnabled(
                    userId, enabled, session.get().userId(), session.get().displayName());
            if (result == MutationResult.NOT_FOUND) {
                return ResponseMessage.failure(request.requestId(), "账号不存在或不可管理");
            }
            if (result == MutationResult.CHANGED) {
                sessions.invalidateUser(userId);
            }
            audit.record(session.get().userId(), Actions.ACCOUNT_SET_ENABLED, "SUCCESS", null);
            return ResponseMessage.success(
                    request.requestId(), enabled ? "账号已启用" : "账号已停用", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage resetPassword(RequestMessage request) {
        Optional<UserSession> session = authorized(request);
        if (session.isEmpty()) {
            return authorizationFailure(request);
        }
        char[] password = request.parameters().getOrDefault("temporaryPassword", "").toCharArray();
        try {
            long userId = parseLong(request.parameters().get("userId"), "账号ID");
            validatePassword(password, "临时密码");
            PasswordHasher.PasswordHash passwordHash = passwordHasher.hash(password);
            MutationResult result = accounts.resetPassword(
                    userId, passwordHash,
                    session.get().userId(), session.get().displayName());
            if (result == MutationResult.NOT_FOUND) {
                return ResponseMessage.failure(request.requestId(), "账号不存在或不可管理");
            }
            if (result == MutationResult.CHANGED) {
                sessions.invalidateUser(userId);
            }
            audit.record(session.get().userId(), Actions.ACCOUNT_RESET_PASSWORD, "SUCCESS", null);
            return ResponseMessage.success(request.requestId(), "密码已重置", Map.of());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Optional<UserSession> authorized(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(session -> AccountAccessPolicy.canManageAccounts(session.roles()));
    }

    private ResponseMessage authorizationFailure(RequestMessage request) {
        if (sessions.find(request.parameters().get("sessionToken")).isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }
        return ResponseMessage.failure(request.requestId(), "无权执行此操作");
    }

    private CreateStudentAccount parseStudent(Map<String, String> values, Set<UserRole> roles) {
        String number = required(values, "number", "学号");
        if (!number.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("学号必须是 10 位数字");
        }
        String fullName = validateName(required(values, "fullName", "姓名"));
        Gender gender;
        try {
            gender = Gender.valueOf(values.getOrDefault("gender", Gender.UNSPECIFIED.name()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("性别选项不正确");
        }
        LocalDate birthDate = parseOptionalDate(values.get("birthDate"));
        int enrollmentYear = parseInteger(values.get("enrollmentYear"), "入学年份", 2000, 2100);
        String phone = trimToNull(values.get("phone"));
        String email = trimToNull(values.get("email"));
        String address = trimToNull(values.get("address"));
        validateContact(phone, email, address);
        return new CreateStudentAccount(
                number, fullName, gender, birthDate,
                parseLong(values.get("departmentId"), "学院ID"),
                parseLong(values.get("majorId"), "专业ID"),
                parseLong(values.get("classId"), "班级ID"),
                enrollmentYear, phone, email, address, roles);
    }

    private CreateTeacherAccount parseTeacher(Map<String, String> values, Set<UserRole> roles) {
        String number = required(values, "number", "教师工号");
        if (!number.matches("T[0-9]{7}")) {
            throw new IllegalArgumentException("教师工号必须是 T 加 7 位数字");
        }
        String fullName = validateName(required(values, "fullName", "姓名"));
        String professionalTitle = required(values, "professionalTitle", "职称");
        if (professionalTitle.length() > 100) {
            throw new IllegalArgumentException("职称不能超过 100 位");
        }
        String phone = trimToNull(values.get("phone"));
        String email = trimToNull(values.get("email"));
        validateContact(phone, email, null);
        return new CreateTeacherAccount(
                number, fullName, parseLong(values.get("departmentId"), "学院ID"),
                professionalTitle, phone, email, roles);
    }

    private Set<UserRole> completeRoles(UserRole identity, String roleValues) {
        Set<UserRole> complete = new LinkedHashSet<>();
        complete.add(identity);
        if (roleValues != null && !roleValues.isBlank()) {
            for (String value : roleValues.split(",")) {
                try {
                    complete.add(UserRole.valueOf(value.trim()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("包含未知管理员角色");
                }
            }
        }
        RoleCompositionPolicy.requireValid(complete);
        return Set.copyOf(complete);
    }

    private UserRole parseRequiredIdentity(String value) {
        UserRole identity = parseOptionalIdentity(value);
        if (identity == null) {
            throw new IllegalArgumentException("请选择学生或教师身份");
        }
        return identity;
    }

    private UserRole parseOptionalIdentity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            UserRole role = UserRole.valueOf(value.trim());
            if (role != UserRole.STUDENT && role != UserRole.TEACHER) {
                throw new IllegalArgumentException("只能选择学生或教师身份");
            }
            return role;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("只能选择学生或教师身份");
        }
    }

    private String encode(AccountSummary account) {
        String roles = account.administrativeRoles().stream()
                .map(UserRole::name)
                .sorted()
                .collect(Collectors.joining(","));
        return RowCodec.encode(
                Long.toString(account.userId()), account.username(), account.displayName(),
                account.baseIdentity().name(), roles,
                Boolean.toString(account.enabled()),
                Boolean.toString(account.forcePasswordChange()),
                account.lastLoginAt() == null ? "" : account.lastLoginAt().toString());
    }

    private void putReferences(Map<String, String> data, String type, List<ReferenceItem> items) {
        data.put(type + ".count", Integer.toString(items.size()));
        for (int index = 0; index < items.size(); index++) {
            ReferenceItem item = items.get(index);
            String prefix = type + "." + index + ".";
            data.put(prefix + "id", Long.toString(item.id()));
            data.put(prefix + "parentId", Long.toString(item.parentId()));
            data.put(prefix + "code", item.code());
            data.put(prefix + "name", item.name());
            data.put(prefix + "year", Integer.toString(item.year()));
        }
    }

    private void validatePassword(char[] password, String label) {
        if (password.length < 8 || password.length > 128) {
            throw new IllegalArgumentException(label + "长度必须为 8—128 位");
        }
    }

    private String validateName(String name) {
        if (name.length() > 100) {
            throw new IllegalArgumentException("姓名不能超过 100 位");
        }
        return name;
    }

    private void validateContact(String phone, String email, String address) {
        if (phone != null && (phone.length() > 32 || !phone.matches("[0-9+() -]+"))) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        if (email != null && (email.length() > 128 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (address != null && address.length() > 255) {
            throw new IllegalArgumentException("地址不能超过 255 位");
        }
    }

    private LocalDate parseOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("出生日期格式必须为 yyyy-MM-dd");
        }
    }

    private int parseInteger(String value, String label, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(label + "必须在 " + minimum + "—" + maximum + " 之间");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是数字");
        }
    }

    private long parseLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(label + "必须大于 0");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + "必须是有效数字");
        }
    }

    private Boolean parseOptionalBoolean(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseRequiredBoolean(value, label);
    }

    private boolean parseRequiredBoolean(String value, String label) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(label + "不正确");
        }
        return Boolean.parseBoolean(value);
    }

    private String required(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请填写" + label);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Account database error: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "账号数据暂时不可用，请稍后重试");
    }
}
