package com.vcampus.server.service;

import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.database.ForumBoostStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumBoostServiceTest {
    private SessionManager sessions;
    private RecordingStore store;
    private ForumBoostService service;
    private String token;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        token = session(7L, Set.of(UserRole.STUDENT));
        store = new RecordingStore();
        service = new ForumBoostService(store, sessions,
                Clock.fixed(Instant.parse("2026-09-15T02:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createUsesSessionIdentityTrustedTierUuidAndShanghaiDay() {
        UUID operationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        var response = service.handle(RequestMessage.create(Actions.FORUM_BOOST_CREATE, Map.of(
                "sessionToken", token, "postId", "11", "tier", "FIVE",
                "operationId", operationId.toString(), "amount", "0.01", "heat", "999999",
                "userId", "999")));

        assertTrue(response.success());
        assertEquals(7L, store.actorUserId);
        assertEquals(11L, store.postId);
        assertEquals(ForumBoostTier.FIVE, store.tier);
        assertEquals(operationId, store.operationId);
        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), store.dayStart);
        assertEquals(Instant.parse("2026-09-15T16:00:00Z"), store.dayEnd);
        assertEquals("5.00", response.data().get("amount"));
        assertEquals("50", response.data().get("initialHeat"));
        assertEquals("FB-" + operationId, response.data().get("bankReference"));
    }

    @Test
    void getSerializesEligibilityUsageBalanceAndAvailableTiers() {
        var response = service.handle(RequestMessage.create(Actions.FORUM_BOOST_GET, Map.of(
                "sessionToken", token, "postId", "11")));

        assertTrue(response.success());
        assertEquals("true", response.data().get("eligible"));
        assertEquals("2", response.data().get("activeBoostCount"));
        assertEquals("75", response.data().get("activeHeat"));
        assertEquals("1", response.data().get("userOperationsUsed"));
        assertEquals("10.00", response.data().get("userAmountUsed"));
        assertEquals("35.00", response.data().get("postAmountUsed"));
        assertEquals("95.00", response.data().get("balance"));
        assertEquals("FIVE,TEN,FIFTEEN", response.data().get("availableTiers"));
    }

    @Test
    void rejectsAdministratorsAndMalformedRequestsBeforeStoreAccess() {
        String admin = session(8L, Set.of(UserRole.TEACHER, UserRole.BANK_ADMIN));
        var denied = service.handle(RequestMessage.create(Actions.FORUM_BOOST_CREATE, Map.of(
                "sessionToken", admin, "postId", "11", "tier", "FIVE",
                "operationId", UUID.randomUUID().toString())));
        var badTier = service.handle(RequestMessage.create(Actions.FORUM_BOOST_CREATE, Map.of(
                "sessionToken", token, "postId", "11", "tier", "TWENTY",
                "operationId", UUID.randomUUID().toString())));
        var badUuid = service.handle(RequestMessage.create(Actions.FORUM_BOOST_CREATE, Map.of(
                "sessionToken", token, "postId", "11", "tier", "FIVE",
                "operationId", "not-a-uuid")));
        var badPost = service.handle(RequestMessage.create(Actions.FORUM_BOOST_GET, Map.of(
                "sessionToken", token, "postId", "0")));

        assertFalse(denied.success());
        assertTrue(denied.message().contains("管理员"));
        assertFalse(badTier.success());
        assertFalse(badUuid.success());
        assertFalse(badPost.success());
        assertEquals(0, store.calls);
    }

    private String session(long userId, Set<UserRole> roles) {
        return sessions.create(new UserAccount(
                userId, "user" + userId, "hash", "salt", "用户" + userId,
                true, false, roles)).token();
    }

    private static final class RecordingStore implements ForumBoostStore {
        int calls;
        long actorUserId;
        long postId;
        ForumBoostTier tier;
        UUID operationId;
        Instant dayStart;
        Instant dayEnd;

        @Override
        public BoostReceipt create(long postId, long actorUserId, ForumBoostTier tier,
                                   UUID operationId, Instant dayStart, Instant dayEnd,
                                   Instant now) throws SQLException {
            calls++;
            this.postId = postId;
            this.actorUserId = actorUserId;
            this.tier = tier;
            this.operationId = operationId;
            this.dayStart = dayStart;
            this.dayEnd = dayEnd;
            return new BoostReceipt(41L, 51L, "FB-" + operationId,
                    tier.amount(), tier.initialHeat(), new BigDecimal("90.00"),
                    3, 125, false);
        }

        @Override
        public BoostState state(long postId, long actorUserId, Instant dayStart,
                                Instant dayEnd, Instant now) throws SQLException {
            calls++;
            this.postId = postId;
            this.actorUserId = actorUserId;
            this.dayStart = dayStart;
            this.dayEnd = dayEnd;
            return new BoostState(true, "", 2, 75, 1,
                    new BigDecimal("10.00"), new BigDecimal("35.00"),
                    new BigDecimal("95.00"), List.of(ForumBoostTier.values()));
        }
    }
}
