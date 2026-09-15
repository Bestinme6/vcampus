package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.database.ForumBoostStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterForumTest {
    @Test
    void routesForumSectionListToForumService() {
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                1L, "2026000001", "hash", "salt", "学生",
                true, false, Set.of(UserRole.STUDENT))).token();
        AtomicInteger calls = new AtomicInteger();
        ForumStore store = (ForumStore) Proxy.newProxyInstance(
                ForumStore.class.getClassLoader(), new Class<?>[]{ForumStore.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "listSections" -> {
                            calls.incrementAndGet();
                            yield List.of();
                        }
                        case "deletePost" -> ForumStore.MutationResult.CHANGED;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        RecordingAuditStore audit = new RecordingAuditStore();
        AtomicInteger boostCalls = new AtomicInteger();
        ForumBoostStore boosts = new ForumBoostStore() {
            @Override public BoostReceipt create(long postId, long actorUserId, ForumBoostTier tier,
                                                 UUID operationId, Instant dayStart, Instant dayEnd,
                                                 Instant now) {
                boostCalls.incrementAndGet();
                return new BoostReceipt(1, 2, "FB-" + operationId, tier.amount(),
                        tier.initialHeat(), new BigDecimal("95.00"), 1, 50, false);
            }
            @Override public BoostState state(long postId, long actorUserId, Instant dayStart,
                                              Instant dayEnd, Instant now) {
                boostCalls.incrementAndGet();
                return new BoostState(true, "", 0, 0, 0,
                        new BigDecimal("0.00"), new BigDecimal("0.00"),
                        new BigDecimal("100.00"), List.of(ForumBoostTier.values()));
            }
        };
        ForumService forum = new ForumService(store, sessions, null, null, boosts, audit);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, null, null, forum, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.FORUM_SECTION_LIST, Map.of("sessionToken", token)), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, calls.get());
        assertTrue(audit.entries().isEmpty());

        ResponseMessage deleted = router.route(RequestMessage.create(
                Actions.FORUM_POST_DELETE, Map.of("sessionToken", token, "postId", "7")),
                "127.0.0.1");
        assertTrue(deleted.success());
        assertEquals(Actions.FORUM_POST_DELETE, audit.last().action());

        ResponseMessage state = router.route(RequestMessage.create(
                Actions.FORUM_BOOST_GET, Map.of("sessionToken", token, "postId", "11")),
                "127.0.0.1");
        ResponseMessage created = router.route(RequestMessage.create(
                Actions.FORUM_BOOST_CREATE, Map.of(
                        "sessionToken", token, "postId", "11", "tier", "FIVE",
                        "operationId", UUID.randomUUID().toString())), "127.0.0.1");
        assertTrue(state.success());
        assertTrue(created.success());
        assertEquals(2, boostCalls.get());
    }
}
