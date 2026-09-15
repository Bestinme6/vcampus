package com.vcampus.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.ShopReviewStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.image.ShopReviewImageStore;
import com.vcampus.server.model.ShopReviewRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopReviewServiceTest {
    private SessionManager sessions;
    private String userToken;
    private String adminToken;
    private AtomicInteger createCalls;
    private RecordingAuditStore audit;
    private ShopReviewService service;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        userToken = sessions.create(account(7, UserRole.STUDENT)).token();
        adminToken = sessions.create(account(9, UserRole.SHOP_ADMIN)).token();
        createCalls = new AtomicInteger();
        audit = new RecordingAuditStore();
        ShopReviewStore store = (ShopReviewStore) Proxy.newProxyInstance(
                ShopReviewStore.class.getClassLoader(), new Class<?>[]{ShopReviewStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "create" -> {
                        createCalls.incrementAndGet();
                        yield new ShopReviewStore.ReviewMutationResult(review(), false, Set.of(), Set.of());
                    }
                    case "update" -> new ShopReviewStore.ReviewMutationResult(review(), false, Set.of(), Set.of());
                    case "delete", "hide", "restore" -> true;
                    case "setLike" -> new ShopReviewStore.LikeResult(41, true, true, 3);
                    case "summary" -> new ShopReviewStore.ReviewSummary(
                            new BigDecimal("4.5"), 2, List.of(0, 0, 0, 1, 1));
                    case "list" -> new ShopReviewStore.ReviewPage(
                            List.of(review()), Map.of(41L, List.of()), 1, 10, 1);
                    case "searchAdmin" -> new ShopReviewStore.AdminReviewPage(
                            List.of(review()), Map.of(41L, List.of()), 1, 10, 1);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        service = new ShopReviewService(store,
                new ShopReviewImageService(new FakeImages(), null, sessions), sessions, audit);
    }

    @Test
    void createAcceptsOnlyClientOwnedFieldsAndEncodesAnonymousPublicRow() {
        Map<String, String> valid = Map.of(
                "sessionToken", userToken,
                "operationId", "00000000-0000-0000-0000-000000000001",
                "orderItemId", "31", "rating", "5", "content", "纸张厚实，书写顺滑",
                "imageCount", "0");
        var response = service.create(RequestMessage.create(Actions.SHOP_REVIEW_CREATE, valid));
        assertTrue(response.success(), response.message());
        assertEquals(List.of("41", "11", "31", "小林", "5", "纸张厚实，书写顺滑",
                        "false", "2", "false", Instant.EPOCH.toString(), Instant.EPOCH.toString()),
                RowCodec.decode(response.data().get("row.0")));
        assertEquals(1, createCalls.get());

        for (String forbidden : List.of("authorUserId", "productId", "repeatPurchase",
                "status", "likeCount", "ratingAverage")) {
            Map<String, String> tampered = new LinkedHashMap<>(valid);
            tampered.put(forbidden, "1");
            assertFalse(service.create(RequestMessage.create(Actions.SHOP_REVIEW_CREATE, tampered)).success());
        }
        assertEquals(1, createCalls.get());
    }

    @Test
    void validatesUuidRatingContentAndAdminPermissionBeforeStoreCalls() {
        for (Map<String, String> values : List.of(
                createValues("not-a-uuid", "5", "内容长度符合要求"),
                createValues("00000000-0000-0000-0000-000000000001", "0", "内容长度符合要求"),
                createValues("00000000-0000-0000-0000-000000000001", "6", "内容长度符合要求"),
                createValues("00000000-0000-0000-0000-000000000001", "5", "四个字"))) {
            assertFalse(service.create(RequestMessage.create(Actions.SHOP_REVIEW_CREATE, values)).success());
        }
        assertEquals(0, createCalls.get());
        assertFalse(service.hide(RequestMessage.create(Actions.SHOP_ADMIN_REVIEW_HIDE,
                Map.of("sessionToken", userToken, "reviewId", "41", "reason", "违规内容"))).success());
        assertTrue(service.hide(RequestMessage.create(Actions.SHOP_ADMIN_REVIEW_HIDE,
                Map.of("sessionToken", adminToken, "reviewId", "41", "reason", "违规内容"))).success());
    }

    @Test
    void listUsesTenRowsAndMutationsAreAudited() {
        var list = service.list(RequestMessage.create(Actions.SHOP_REVIEW_LIST,
                Map.of("sessionToken", userToken, "productId", "11", "page", "1")));
        assertTrue(list.success());
        assertEquals("4.5", list.data().get("ratingAverage"));
        assertEquals("10", list.data().get("pageSize"));
        service.delete(RequestMessage.create(Actions.SHOP_REVIEW_DELETE,
                Map.of("sessionToken", userToken, "reviewId", "41")));
        service.setLike(RequestMessage.create(Actions.SHOP_REVIEW_LIKE_SET,
                Map.of("sessionToken", userToken, "reviewId", "41", "enabled", "true")));
        assertEquals(Set.of(Actions.SHOP_REVIEW_DELETE, Actions.SHOP_REVIEW_LIKE_SET),
                audit.entries().stream().map(RecordingAuditStore.Entry::action)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private Map<String, String> createValues(String operationId, String rating, String content) {
        return Map.of("sessionToken", userToken, "operationId", operationId,
                "orderItemId", "31", "rating", rating, "content", content, "imageCount", "0");
    }

    private ShopReviewRecord review() {
        return new ShopReviewRecord(41, 31, 11, 7, "小林", 5, "纸张厚实，书写顺滑",
                false, ShopReviewStatus.NORMAL, 2, false, Instant.EPOCH, Instant.EPOCH);
    }

    private UserAccount account(long id, UserRole role) {
        return new UserAccount(id, "user" + id, "hash", "salt", id == 7 ? "小林" : "管理员",
                true, false, role == UserRole.SHOP_ADMIN
                        ? Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN) : Set.of(role));
    }

    private static final class FakeImages implements ShopReviewImageStore {
        public ShopImageStore.UploadTicket start(long ownerId, String mimeType, long bytes) {
            throw new UnsupportedOperationException();
        }
        public void append(long ownerId, String uploadId, int index, byte[] bytes) { }
        public Completed complete(long ownerId, String uploadId) { throw new UnsupportedOperationException(); }
        public ShopImageStore.ImageDescriptor describe(String storageKey) { throw new UnsupportedOperationException(); }
        public ShopImageStore.ImageRange readRange(String storageKey, long offset, int maxBytes) { throw new UnsupportedOperationException(); }
        public void cleanup(Set<String> referencedKeys, Instant now) { }
    }
}
