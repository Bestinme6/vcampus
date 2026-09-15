package com.vcampus.server.service;

import com.vcampus.common.model.ForumAccessPolicy;
import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.ForumBoostRuleException;
import com.vcampus.server.database.ForumBoostStore;
import com.vcampus.server.security.SessionManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ForumBoostService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final ForumBoostStore store;
    private final SessionManager sessions;
    private final Clock clock;

    public ForumBoostService(ForumBoostStore store, SessionManager sessions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ResponseMessage handle(RequestMessage request) {
        var session = sessions.find(request.parameters().get("sessionToken"));
        if (session.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }
        var actor = session.orElseThrow();
        if (actor.forcePasswordChange()) {
            return ResponseMessage.failure(request.requestId(), "请先修改初始密码");
        }
        if (!ForumAccessPolicy.canUse(actor.roles())) {
            return ResponseMessage.failure(request.requestId(), "无权访问论坛");
        }
        if (!ForumAccessPolicy.canBoost(actor.roles())) {
            return ResponseMessage.failure(request.requestId(), "论坛管理员和超级管理员不能购买论坛助力");
        }
        try {
            long postId = positiveLong(request.parameters().get("postId"));
            Instant now = clock.instant();
            LocalDate day = LocalDate.now(clock.withZone(SHANGHAI));
            Instant dayStart = day.atStartOfDay(SHANGHAI).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
            Map<String, String> data;
            if (Actions.FORUM_BOOST_GET.equals(request.action())) {
                data = state(store.state(
                        postId, actor.userId(), dayStart, dayEnd, now));
            } else if (Actions.FORUM_BOOST_CREATE.equals(request.action())) {
                ForumBoostTier tier = ForumBoostTier.valueOf(required(request, "tier"));
                UUID operationId = UUID.fromString(required(request, "operationId"));
                data = receipt(store.create(
                        postId, actor.userId(), tier, operationId, dayStart, dayEnd, now));
            } else {
                return ResponseMessage.failure(request.requestId(), "不支持的论坛助力操作");
            }
            return ResponseMessage.success(request.requestId(), "操作成功", data);
        } catch (ForumBoostRuleException | BankRuleException exception) {
            return ResponseMessage.failure(request.requestId(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return ResponseMessage.failure(request.requestId(), "论坛助力参数无效");
        } catch (SQLException exception) {
            System.err.println("Forum boost database operation failed: " + exception.getMessage());
            return ResponseMessage.failure(request.requestId(), "论坛助力暂不可用，请稍后重试");
        }
    }

    private Map<String, String> state(ForumBoostStore.BoostState state) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("eligible", Boolean.toString(state.eligible()));
        data.put("reason", state.reason());
        data.put("activeBoostCount", Integer.toString(state.activeBoostCount()));
        data.put("activeHeat", Integer.toString(state.activeHeat()));
        data.put("userOperationsUsed", Integer.toString(state.userOperationsUsed()));
        data.put("userAmountUsed", state.userAmountUsed().toPlainString());
        data.put("postAmountUsed", state.postAmountUsed().toPlainString());
        data.put("balance", state.balance().toPlainString());
        data.put("availableTiers", state.availableTiers().stream()
                .map(Enum::name).collect(Collectors.joining(",")));
        return data;
    }

    private Map<String, String> receipt(ForumBoostStore.BoostReceipt receipt) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("boostId", Long.toString(receipt.boostId()));
        data.put("bankLedgerId", Long.toString(receipt.bankLedgerId()));
        data.put("bankReference", receipt.bankReference());
        data.put("amount", receipt.amount().toPlainString());
        data.put("initialHeat", Integer.toString(receipt.initialHeat()));
        data.put("balanceAfter", receipt.balanceAfter().toPlainString());
        data.put("activeBoostCount", Integer.toString(receipt.activeBoostCount()));
        data.put("activeHeat", Integer.toString(receipt.activeHeat()));
        data.put("duplicate", Boolean.toString(receipt.duplicate()));
        return data;
    }

    private String required(RequestMessage request, String key) {
        String value = request.parameters().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value == null ? "" : value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("postId must be positive");
        }
        return parsed;
    }
}
