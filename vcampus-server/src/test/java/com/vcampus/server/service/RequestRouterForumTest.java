package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.ForumStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
                    if (method.getName().equals("listSections")) {
                        calls.incrementAndGet();
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ForumService forum = new ForumService(store, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, null, null, forum, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.FORUM_SECTION_LIST, Map.of("sessionToken", token)), "127.0.0.1");

        assertTrue(response.success());
        assertEquals(1, calls.get());
    }
}
