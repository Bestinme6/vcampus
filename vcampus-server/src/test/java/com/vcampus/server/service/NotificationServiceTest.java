package com.vcampus.server.service;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.NotificationStore;
import com.vcampus.server.database.NotificationStore.NotificationPage;
import com.vcampus.server.database.NotificationStore.NotificationQuery;
import com.vcampus.server.model.NotificationRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {
    private FakeNotificationStore store;
    private SessionManager sessions;
    private NotificationService service;
    private SessionManager.UserSession userSession;

    @BeforeEach
    void setUp() {
        store = new FakeNotificationStore();
        sessions = new SessionManager();
        service = new NotificationService(store, sessions);
        userSession = sessions.create(new UserAccount(
                1L, "2026000001", "hash", "salt", "张三",
                true, false, Set.of(UserRole.STUDENT)));
    }

    @Test
    void searchAlwaysUsesSessionRecipientAndEncodesCardFields() {
        store.page = new NotificationPage(List.of(record(7L, 1L, false)), 1, 10, 1);

        ResponseMessage response = service.search(request(
                Actions.NOTIFICATION_SEARCH,
                Map.of(
                        "recipientUserId", "2",
                        "keyword", "成绩",
                        "source", "ACADEMIC",
                        "read", "false",
                        "page", "1")));

        assertTrue(response.success());
        assertEquals(1L, store.lastRecipientUserId);
        assertEquals("成绩", store.lastQuery.keyword());
        assertEquals(NotificationSource.ACADEMIC, store.lastQuery.source());
        assertEquals(Boolean.FALSE, store.lastQuery.read());
        assertEquals(10, store.lastQuery.pageSize());
        assertEquals(List.of(
                "7", "GRADE_PUBLISHED", "ACADEMIC", "成绩发布通知",
                "完整正文：《数据库系统原理》的最终成绩已发布。", "STUDENT_GRADES", "31",
                "false", "2026-08-26T10:00:00Z"),
                RowCodec.decode(response.data().get("row.0")));
    }

    @Test
    void detailReturnsFullOwnedMessageAndHidesForeignOrMissingMessage() {
        store.owned = Optional.of(record(7L, 1L, false));
        ResponseMessage detail = service.get(request(
                Actions.NOTIFICATION_GET, Map.of("notificationId", "7")));

        assertTrue(detail.success());
        assertEquals("完整正文：《数据库系统原理》的最终成绩已发布。",
                detail.data().get("content"));
        assertEquals("STUDENT_GRADES", detail.data().get("target"));
        assertEquals(1L, store.lastRecipientUserId);

        store.owned = Optional.empty();
        ResponseMessage hidden = service.get(request(
                Actions.NOTIFICATION_GET, Map.of("notificationId", "99")));
        assertFalse(hidden.success());
        assertEquals("消息不存在", hidden.message());
    }

    @Test
    void unreadAndReadOperationsAreSessionScoped() {
        store.unread = 12;
        store.markReadResult = true;
        store.markAllResult = 5;

        ResponseMessage count = service.unreadCount(request(
                Actions.NOTIFICATION_UNREAD_COUNT, Map.of()));
        ResponseMessage one = service.markRead(request(
                Actions.NOTIFICATION_MARK_READ, Map.of("notificationId", "7")));
        ResponseMessage all = service.markAllRead(request(
                Actions.NOTIFICATION_MARK_ALL_READ, Map.of()));

        assertEquals("12", count.data().get("unreadCount"));
        assertTrue(one.success());
        assertEquals(7L, store.lastNotificationId);
        assertEquals("5", all.data().get("updated"));
        assertEquals(1L, store.lastRecipientUserId);
    }

    @Test
    void rejectsExpiredSessionAndInvalidFiltersBeforeCallingStore() {
        ResponseMessage expired = service.search(RequestMessage.create(
                Actions.NOTIFICATION_SEARCH, Map.of("sessionToken", "missing")));
        ResponseMessage invalid = service.search(request(
                Actions.NOTIFICATION_SEARCH, Map.of("source", "NOT_A_SOURCE")));

        assertFalse(expired.success());
        assertEquals("登录已过期，请重新登录", expired.message());
        assertFalse(invalid.success());
        assertEquals("消息筛选条件无效", invalid.message());
        assertEquals(0, store.searchCalls);
    }

    private NotificationRecord record(long id, long recipientUserId, boolean read) {
        return new NotificationRecord(
                id, recipientUserId, 20L,
                NotificationType.GRADE_PUBLISHED, NotificationSource.ACADEMIC,
                "成绩发布通知", "完整正文：《数据库系统原理》的最终成绩已发布。",
                NotificationTarget.STUDENT_GRADES, 31L, read, null,
                Instant.parse("2026-08-26T10:00:00Z"));
    }

    private RequestMessage request(String action, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", userSession.token());
        return RequestMessage.create(action, parameters);
    }

    private static final class FakeNotificationStore implements NotificationStore {
        private long lastRecipientUserId;
        private long lastNotificationId;
        private int searchCalls;
        private int unread;
        private int markAllResult;
        private boolean markReadResult;
        private NotificationQuery lastQuery;
        private NotificationPage page = new NotificationPage(List.of(), 1, 10, 0);
        private Optional<NotificationRecord> owned = Optional.empty();

        @Override
        public NotificationPage search(long recipientUserId, NotificationQuery query) {
            searchCalls++;
            lastRecipientUserId = recipientUserId;
            lastQuery = query;
            return page;
        }

        @Override
        public Optional<NotificationRecord> findOwned(long recipientUserId, long notificationId) {
            lastRecipientUserId = recipientUserId;
            lastNotificationId = notificationId;
            return owned;
        }

        @Override
        public int unreadCount(long recipientUserId) {
            lastRecipientUserId = recipientUserId;
            return unread;
        }

        @Override
        public boolean markRead(long recipientUserId, long notificationId) {
            lastRecipientUserId = recipientUserId;
            lastNotificationId = notificationId;
            return markReadResult;
        }

        @Override
        public int markAllRead(long recipientUserId) {
            lastRecipientUserId = recipientUserId;
            return markAllResult;
        }
    }
}
