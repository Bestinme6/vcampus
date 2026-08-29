package com.vcampus.server.service;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.server.database.BankStore;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterBankTest {
    @Test
    void routesBankAccountRequestToBankService() {
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                1L, "student", "hash", "salt", "张同学",
                true, false, Set.of(UserRole.STUDENT))).token();
        AtomicInteger calls = new AtomicInteger();
        BankStore store = (BankStore) Proxy.newProxyInstance(
                BankStore.class.getClassLoader(), new Class<?>[]{BankStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("account")) {
                        calls.incrementAndGet();
                        Instant now = Instant.parse("2026-08-29T10:00:00Z");
                        return new BankAccountRecord(2L, 1L, "student", "张同学",
                                new BigDecimal("0.00"), BankAccountStatus.ACTIVE, now, now);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BankService bank = new BankService(store, sessions);
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, null, null, null, bank, sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.BANK_ACCOUNT_GET, Map.of("sessionToken", token)), "127.0.0.1");

        assertTrue(response.success());
        assertEquals("0.00", response.data().get("balance"));
        assertEquals(1, calls.get());
    }
}
