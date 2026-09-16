package com.vcampus.server.service;

import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopAddressStore;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ShopAddressService {
    private final ShopAddressStore addresses;
    private final SessionManager sessions;

    public ShopAddressService(ShopAddressStore addresses, SessionManager sessions) {
        this.addresses = Objects.requireNonNull(addresses, "addresses");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage list(RequestMessage request) {
        return handle(request, session -> {
            requireExact(request, Set.of("sessionToken"), "收货地址查询参数无效");
            var rows = addresses.list(session.userId());
            Map<String, String> data = new LinkedHashMap<>();
            data.put("count", Integer.toString(rows.size()));
            for (int index = 0; index < rows.size(); index++) {
                data.put("row." + index, encode(rows.get(index)));
            }
            return ResponseMessage.success(request.requestId(), "收货地址查询成功", data);
        });
    }

    public ResponseMessage save(RequestMessage request) {
        return handle(request, session -> {
            Set<String> keys = request.parameters().keySet();
            Set<String> createKeys = Set.of("sessionToken", "recipient", "phone", "address");
            Set<String> replaceKeys = Set.of("sessionToken", "recipient", "phone", "address", "replaceSlot");
            if (!keys.equals(createKeys) && !keys.equals(replaceKeys)) {
                throw new IllegalArgumentException("收货地址保存参数无效");
            }
            Integer replaceSlot = keys.contains("replaceSlot")
                    ? slot(request.parameters().get("replaceSlot"), "替换槽位") : null;
            ShippingAddressValue value = new ShippingAddressValue(
                    request.parameters().get("recipient"),
                    request.parameters().get("phone"),
                    request.parameters().get("address"));
            ShopAddress saved = addresses.save(session.userId(), replaceSlot, value);
            return ResponseMessage.success(request.requestId(), "收货地址已保存",
                    Map.of("row", encode(saved)));
        });
    }

    public ResponseMessage delete(RequestMessage request) {
        return handle(request, session -> {
            requireExact(request, Set.of("sessionToken", "slot"), "收货地址删除参数无效");
            boolean changed = addresses.delete(session.userId(),
                    slot(request.parameters().get("slot"), "地址槽位"));
            return ResponseMessage.success(request.requestId(),
                    changed ? "收货地址已删除" : "收货地址不存在",
                    Map.of("changed", Boolean.toString(changed)));
        });
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> found = sessions.find(request.parameters().get("sessionToken"));
        if (found.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }
        try {
            return work.run(found.get());
        } catch (IllegalArgumentException failure) {
            return ResponseMessage.failure(request.requestId(), failure.getMessage());
        } catch (SQLException failure) {
            return ResponseMessage.failure(request.requestId(), "收货地址暂不可用，请稍后重试");
        }
    }

    private void requireExact(RequestMessage request, Set<String> expected, String message) {
        if (!request.parameters().keySet().equals(expected)) {
            throw new IllegalArgumentException(message);
        }
    }

    private int slot(String value, String label) {
        final int parsed;
        try {
            parsed = Integer.parseInt(Objects.requireNonNullElse(value, "").trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + "无效");
        }
        if (parsed < 1 || parsed > 3) throw new IllegalArgumentException(label + "无效");
        return parsed;
    }

    private String encode(ShopAddress row) {
        return RowCodec.encode(Integer.toString(row.slot()), row.version(),
                row.value().recipient(), row.value().phone(), row.value().address());
    }

    @FunctionalInterface
    private interface Work {
        ResponseMessage run(UserSession session) throws SQLException;
    }
}
