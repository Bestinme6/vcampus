package com.vcampus.server.service;

import com.vcampus.common.model.AccessPolicy;
import com.vcampus.common.model.BankAccessPolicy;
import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.BankStore;
import com.vcampus.server.database.BankStore.AccountPage;
import com.vcampus.server.database.BankStore.AccountQuery;
import com.vcampus.server.database.BankStore.LedgerPage;
import com.vcampus.server.database.BankStore.LedgerQuery;
import com.vcampus.server.database.BankStore.StatusResult;
import com.vcampus.server.database.BankStore.TopUpResult;
import com.vcampus.server.database.BankStore.TransferResult;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BankService {
    private static final int PAGE_SIZE = 10;
    private final BankStore bank;
    private final SessionManager sessions;

    public BankService(BankStore bank, SessionManager sessions) {
        this.bank = bank;
        this.sessions = sessions;
    }

    public ResponseMessage account(RequestMessage request) {
        Optional<UserSession> session = bankSession(request);
        if (session.isEmpty()) return accessFailure(request);
        try {
            BankAccountRecord account = bank.account(session.get().userId());
            return ResponseMessage.success(request.requestId(), "查询成功", accountData(account));
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage transfer(RequestMessage request) {
        Optional<UserSession> session = bankSession(request);
        if (session.isEmpty()) return accessFailure(request);
        try {
            String recipient = required(request.parameters(), "recipientUsername", "收款账号");
            TransferResult result = bank.transfer(
                    session.get().userId(), recipient,
                    MoneyPolicy.parsePositive(request.parameters().get("amount")),
                    operationId(request.parameters().get("operationId")));
            return ResponseMessage.success(request.requestId(),
                    result.duplicate() ? "该转账已经处理" : "转账成功",
                    Map.of(
                            "balanceAfter", MoneyPolicy.format(result.senderBalanceAfter()),
                            "recipientBalanceAfter", MoneyPolicy.format(result.recipientBalanceAfter()),
                            "referenceNo", result.referenceNo(),
                            "duplicate", Boolean.toString(result.duplicate())));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (BankRuleException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchLedger(RequestMessage request) {
        Optional<UserSession> session = bankSession(request);
        if (session.isEmpty()) return accessFailure(request);
        try {
            String targetUsername = session.get().username();
            if (BankAccessPolicy.canManage(session.get().roles())) {
                targetUsername = hasText(request.parameters().get("targetUsername"))
                        ? request.parameters().get("targetUsername").trim() : null;
            }
            int page = positiveInt(request.parameters().get("page"), 1);
            BankLedgerType type = optionalLedgerType(request.parameters().get("type"));
            LedgerPage result = bank.searchLedger(
                    new LedgerQuery(targetUsername, type, page, PAGE_SIZE));
            Map<String, String> data = pageData(result.page(), result.pageSize(), result.total(),
                    result.rows().size());
            for (int index = 0; index < result.rows().size(); index++) {
                data.put("row." + index, encodeLedger(result.rows().get(index)));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage searchAccounts(RequestMessage request) {
        Optional<UserSession> session = adminSession(request);
        if (session.isEmpty()) return adminFailure(request);
        try {
            int page = positiveInt(request.parameters().get("page"), 1);
            BankAccountStatus status = optionalStatus(request.parameters().get("status"));
            AccountPage result = bank.searchAccounts(new AccountQuery(
                    request.parameters().get("keyword"), status, page, PAGE_SIZE));
            Map<String, String> data = pageData(result.page(), result.pageSize(), result.total(),
                    result.rows().size());
            for (int index = 0; index < result.rows().size(); index++) {
                data.put("row." + index, encodeAccount(result.rows().get(index)));
            }
            return ResponseMessage.success(request.requestId(), "查询成功", data);
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "银行筛选条件无效");
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage topUp(RequestMessage request) {
        Optional<UserSession> session = adminSession(request);
        if (session.isEmpty()) return adminFailure(request);
        try {
            TopUpResult result = bank.topUp(
                    session.get().userId(),
                    required(request.parameters(), "targetUsername", "目标用户名或学号"),
                    MoneyPolicy.parsePositive(request.parameters().get("amount")),
                    operationId(request.parameters().get("operationId")));
            return ResponseMessage.success(request.requestId(),
                    result.duplicate() ? "该充值已经处理" : "充值成功",
                    Map.of(
                            "accountId", Long.toString(result.accountId()),
                            "balanceAfter", MoneyPolicy.format(result.balanceAfter()),
                            "referenceNo", result.referenceNo(),
                            "duplicate", Boolean.toString(result.duplicate())));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (BankRuleException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    public ResponseMessage freeze(RequestMessage request) {
        return changeStatus(request, BankAccountStatus.FROZEN);
    }

    public ResponseMessage unfreeze(RequestMessage request) {
        return changeStatus(request, BankAccountStatus.ACTIVE);
    }

    private ResponseMessage changeStatus(RequestMessage request, BankAccountStatus status) {
        Optional<UserSession> session = adminSession(request);
        if (session.isEmpty()) return adminFailure(request);
        try {
            StatusResult result = bank.setStatus(
                    session.get().userId(),
                    required(request.parameters(), "targetUsername", "目标用户名或学号"),
                    status);
            return ResponseMessage.success(request.requestId(),
                    result.changed() ? (status == BankAccountStatus.FROZEN ? "账户已冻结" : "账户已解冻")
                            : "账户状态未变化",
                    Map.of("accountId", Long.toString(result.accountId()),
                            "status", result.status().name(),
                            "changed", Boolean.toString(result.changed())));
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (BankRuleException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (SQLException exception) {
            return databaseFailure(request, exception);
        }
    }

    private Optional<UserSession> bankSession(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(session -> AccessPolicy.canAccess(ModuleCode.BANK, session.roles()));
    }

    private Optional<UserSession> adminSession(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken"))
                .filter(session -> BankAccessPolicy.canManage(session.roles()));
    }

    private ResponseMessage accessFailure(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken")).isEmpty()
                ? ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录")
                : ResponseMessage.failure(request.requestId(), "无权使用虚拟银行");
    }

    private ResponseMessage adminFailure(RequestMessage request) {
        return sessions.find(request.parameters().get("sessionToken")).isEmpty()
                ? ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录")
                : ResponseMessage.failure(request.requestId(), "无权执行银行管理操作");
    }

    private Map<String, String> accountData(BankAccountRecord account) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("accountId", Long.toString(account.id()));
        data.put("username", account.username());
        data.put("displayName", account.displayName());
        data.put("balance", MoneyPolicy.format(account.balance()));
        data.put("status", account.status().name());
        data.put("updatedAt", account.updatedAt().toString());
        return data;
    }

    private String encodeAccount(BankAccountRecord account) {
        return RowCodec.encode(Long.toString(account.id()), Long.toString(account.userId()),
                account.username(), account.displayName(), MoneyPolicy.format(account.balance()),
                account.status().name(), account.createdAt().toString(), account.updatedAt().toString());
    }

    private String encodeLedger(BankLedgerRecord row) {
        return RowCodec.encode(Long.toString(row.id()), Long.toString(row.accountId()),
                row.type().name(), row.direction().name(), MoneyPolicy.format(row.amount()),
                MoneyPolicy.format(row.balanceAfter()), row.referenceNo(),
                nullable(row.counterpartyUserId()), nullable(row.operatorUserId()),
                row.description(), row.createdAt().toString());
    }

    private Map<String, String> pageData(int page, int pageSize, int total, int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", Integer.toString(page));
        data.put("pageSize", Integer.toString(pageSize));
        data.put("total", Integer.toString(total));
        data.put("count", Integer.toString(count));
        return data;
    }

    private String operationId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("业务编号无效");
        }
    }

    private String required(Map<String, String> values, String key, String label) {
        String value = values.get(key);
        if (!hasText(value)) throw new IllegalArgumentException("请填写" + label);
        return value.trim();
    }

    private int positiveInt(String value, int defaultValue) {
        if (!hasText(value)) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new IllegalArgumentException("页码无效");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("页码无效");
        }
    }

    private BankLedgerType optionalLedgerType(String value) {
        if (!hasText(value)) return null;
        try {
            return BankLedgerType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("流水类型无效");
        }
    }

    private BankAccountStatus optionalStatus(String value) {
        if (!hasText(value)) return null;
        return BankAccountStatus.valueOf(value.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullable(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private ResponseMessage databaseFailure(RequestMessage request, SQLException exception) {
        System.err.println("Bank database operation failed: " + exception.getMessage());
        return ResponseMessage.failure(request.requestId(), "数据库操作失败，请稍后重试");
    }
}
