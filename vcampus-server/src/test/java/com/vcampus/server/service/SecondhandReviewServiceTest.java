package com.vcampus.server.service;

import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.SecondhandReviewStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandReviewServiceTest {
    private String buyer;
    private String ordinary;
    private String admin;
    private AtomicLong actor;
    private SecondhandReviewService service;

    @BeforeEach
    void setUp() {
        SessionManager sessions = new SessionManager();
        buyer = token(sessions, 2L, Set.of(UserRole.STUDENT));
        ordinary = token(sessions, 3L, Set.of(UserRole.STUDENT));
        admin = token(sessions, 9L, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        actor = new AtomicLong();
        SecondhandReviewStore store = (SecondhandReviewStore) Proxy.newProxyInstance(
                SecondhandReviewStore.class.getClassLoader(),
                new Class<?>[]{SecondhandReviewStore.class}, (proxy, method, arguments) -> {
                    if (arguments != null && arguments.length > 0 && arguments[0] instanceof Long id) {
                        actor.set(id);
                    }
                    return switch (method.getName()) {
                        case "findCreated" -> java.util.Optional.empty();
                        case "create", "update" -> new SecondhandReviewStore.MutationResult(
                                review(), false, Set.of());
                        case "delete", "moderate" -> true;
                        case "setLike" -> new SecondhandReviewStore.LikeResult(41L, true, true, 3);
                        case "summary" -> new SecondhandReviewStore.Summary(
                                new BigDecimal("4.5"), 2, List.of(0, 0, 0, 1, 1));
                        case "listSeller", "searchAdmin" -> new SecondhandReviewStore.ReviewPage(
                                List.of(review()), Map.of(), 1, 10, 1);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        service = new SecondhandReviewService(store, null, sessions);
    }

    @Test
    void createUsesSessionBuyerAndReturnsSellerRatingData() {
        Map<String, String> values = new LinkedHashMap<>(Map.of(
                "operationId", UUID.randomUUID().toString(), "orderId", "10",
                "rating", "5", "content", "物品与描述一致", "imageCount", "0"));
        values.put("buyerId", "999");

        var response = service.create(request(Actions.SHOP_USED_REVIEW_CREATE, buyer, values));
        assertEquals(2L, actor.get());
        var listed = service.list(request(Actions.SHOP_USED_REVIEW_LIST, ordinary,
                Map.of("sellerId", "1", "page", "1")));

        assertTrue(response.success(), response.message());
        assertTrue(listed.success(), listed.message());
        assertEquals("4.5", listed.data().get("ratingAverage"));
        assertEquals(List.of("41", "10", "7", "1", "2", "买家", "5", "物品与描述一致",
                        "NORMAL", "2", "false", Instant.EPOCH.toString(), Instant.EPOCH.toString()),
                RowCodec.decode(listed.data().get("row.0")));
    }

    @Test
    void validatesReviewAndProtectsAdministrativeActions() {
        assertFalse(service.create(request(Actions.SHOP_USED_REVIEW_CREATE, buyer,
                Map.of("operationId", "bad", "orderId", "10", "rating", "5",
                        "content", "物品与描述一致", "imageCount", "0"))).success());
        assertFalse(service.hide(request(Actions.SHOP_USED_ADMIN_REVIEW_HIDE, ordinary,
                Map.of("reviewId", "41", "reason", "违规内容"))).success());
        assertTrue(service.hide(request(Actions.SHOP_USED_ADMIN_REVIEW_HIDE, admin,
                Map.of("reviewId", "41", "reason", "违规内容"))).success());
        assertEquals(9L, actor.get());
    }

    private static SecondhandReviewStore.Review review() {
        return new SecondhandReviewStore.Review(41L, 10L, 7L, 1L, 2L, "买家", 5,
                "物品与描述一致", ShopReviewStatus.NORMAL, 2, false,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values);
        all.put("sessionToken", token);
        return RequestMessage.create(action, all);
    }

    private static String token(SessionManager sessions, long id, Set<UserRole> roles) {
        return sessions.create(new UserAccount(id, "user" + id, "h", "s", "用户" + id,
                true, false, roles)).token();
    }
}
