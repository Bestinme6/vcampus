package com.vcampus.server.service;

import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.SecondhandListingStore;
import com.vcampus.server.database.SecondhandListingStore.Listing;
import com.vcampus.server.database.SecondhandListingStore.ListingInput;
import com.vcampus.server.database.SecondhandRuleException;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.security.SessionManager.UserSession;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SecondhandListingService {
    private static final int PAGE_SIZE = 12;
    private final SecondhandListingStore listings;
    private final SessionManager sessions;

    public SecondhandListingService(SecondhandListingStore listings, SessionManager sessions) {
        this.listings = Objects.requireNonNull(listings, "listings");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public ResponseMessage search(RequestMessage request) {
        return handle(request, session -> {
            var page = listings.search(request.parameters().get("keyword"),
                    positiveInt(request.parameters().get("page"), "页码"), PAGE_SIZE);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("page", Integer.toString(page.page()));
            data.put("pageSize", Integer.toString(page.pageSize()));
            data.put("total", Integer.toString(page.total()));
            data.put("count", Integer.toString(page.rows().size()));
            for (int index = 0; index < page.rows().size(); index++) {
                data.put("row." + index, encode(page.rows().get(index)));
            }
            return ResponseMessage.success(request.requestId(), "二手物品查询成功", data);
        });
    }

    public ResponseMessage get(RequestMessage request) {
        return handle(request, session -> {
            Listing row = listings.get(positiveLong(request.parameters().get("itemId"), "物品ID"),
                    session.userId(), ShopAccessPolicy.canManage(session.roles()));
            return ResponseMessage.success(request.requestId(), "二手物品详情查询成功",
                    Map.of("row", encode(row)));
        });
    }

    public ResponseMessage create(RequestMessage request) {
        return handle(request, session -> {
            long id = listings.create(session.userId(), input(request));
            return ResponseMessage.success(request.requestId(), "二手物品已发布",
                    Map.of("itemId", Long.toString(id)));
        });
    }

    public ResponseMessage update(RequestMessage request) {
        return handle(request, session -> {
            Listing row = listings.update(session.userId(),
                    positiveLong(request.parameters().get("itemId"), "物品ID"), input(request));
            return ResponseMessage.success(request.requestId(), "二手物品已更新",
                    Map.of("row", encode(row)));
        });
    }

    public ResponseMessage withdraw(RequestMessage request) {
        return handle(request, session -> {
            Listing row = listings.withdraw(session.userId(),
                    positiveLong(request.parameters().get("itemId"), "物品ID"));
            return ResponseMessage.success(request.requestId(), "二手物品已下架",
                    Map.of("row", encode(row)));
        });
    }

    public ResponseMessage hide(RequestMessage request) {
        return moderate(request, false);
    }

    public ResponseMessage restore(RequestMessage request) {
        return moderate(request, true);
    }

    private ResponseMessage moderate(RequestMessage request, boolean visible) {
        return handle(request, session -> {
            if (!ShopAccessPolicy.canManage(session.roles())) {
                throw new SecondhandRuleException("无权审核二手物品");
            }
            Listing row = listings.moderate(session.userId(),
                    positiveLong(request.parameters().get("itemId"), "物品ID"), visible,
                    request.parameters().get("reason"));
            return ResponseMessage.success(request.requestId(),
                    visible ? "二手物品已恢复" : "二手物品已隐藏", Map.of("row", encode(row)));
        });
    }

    private ListingInput input(RequestMessage request) {
        return new ListingInput(request.parameters().get("title"),
                request.parameters().get("description"), request.parameters().get("category"),
                request.parameters().get("condition"),
                MoneyPolicy.parsePositive(request.parameters().get("price")));
    }

    private ResponseMessage handle(RequestMessage request, Work work) {
        Optional<UserSession> found = sessions.find(request.parameters().get("sessionToken"));
        if (found.isEmpty()) {
            return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
        }
        try {
            return work.run(found.get());
        } catch (SecondhandRuleException | IllegalArgumentException error) {
            return ResponseMessage.failure(request.requestId(), error.getMessage());
        } catch (SQLException error) {
            System.err.println("Secondhand listing database operation failed: " + error.getMessage());
            return ResponseMessage.failure(request.requestId(), "二手市场暂不可用，请稍后重试");
        }
    }

    private static String encode(Listing row) {
        return RowCodec.encode(Long.toString(row.id()), Long.toString(row.sellerId()),
                row.sellerName(), row.title(), row.description(), row.category(), row.condition(),
                MoneyPolicy.format(row.price()), row.status().name(), row.moderation().name(),
                row.createdAt().toString(), row.updatedAt().toString());
    }

    private static long positiveLong(String value, String label) {
        try {
            long parsed = Long.parseLong(Objects.requireNonNullElse(value, "").trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    private static int positiveInt(String value, String label) {
        long parsed = positiveLong(value, label);
        if (parsed > Integer.MAX_VALUE) throw new IllegalArgumentException(label + "无效");
        return (int) parsed;
    }

    @FunctionalInterface
    private interface Work {
        ResponseMessage run(UserSession session) throws SQLException;
    }
}
