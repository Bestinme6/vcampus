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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRouterBankTest {
    @Test
    void recipientRouteHonorsForcedPasswordChangeAndReadOnlyContract() {
        SessionManager sessions=new SessionManager();
        BankStore store=(BankStore)Proxy.newProxyInstance(BankStore.class.getClassLoader(),new Class<?>[]{BankStore.class},
                (proxy,method,args)->{
                    if(method.getName().equals("recipient"))return new BankStore.Recipient(2,"teacher","李老师");
                    throw new AssertionError("核对收款人不应访问 "+method.getName());
                });
        RequestRouter router=new RequestRouter(null,null,null,null,null,null,null,null,new BankService(store,sessions),sessions);
        String token=sessions.create(new UserAccount(1,"student","hash","salt","同学",true,true,Set.of(UserRole.STUDENT))).token();
        var blocked=router.route(RequestMessage.create(Actions.BANK_RECIPIENT_GET,Map.of("sessionToken",token,"recipientUsername","teacher")),"127.0.0.1");
        org.junit.jupiter.api.Assertions.assertFalse(blocked.success());
        String ready=sessions.create(new UserAccount(3,"other","hash","salt","同学",true,false,Set.of(UserRole.STUDENT))).token();
        var response=router.route(RequestMessage.create(Actions.BANK_RECIPIENT_GET,Map.of("sessionToken",ready,"recipientUsername","teacher")),"127.0.0.1");
        assertTrue(response.success());
        assertEquals(Map.of("username","teacher","displayName","李老师","self","false"),response.data());
    }
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

    @Test
    void routesReadOnlyBankSummaryWithoutCallingAccountOpen() {
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(
                1L, "student", "hash", "salt", "张同学",
                true, false, Set.of(UserRole.STUDENT))).token();
        AtomicInteger summaryCalls = new AtomicInteger();
        BankStore store = (BankStore) Proxy.newProxyInstance(
                BankStore.class.getClassLoader(), new Class<?>[]{BankStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("accountSummary")) {
                        summaryCalls.incrementAndGet();
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RequestRouter router = new RequestRouter(
                null, null, null, null, null, null, null, null,
                new BankService(store, sessions), sessions);

        ResponseMessage response = router.route(RequestMessage.create(
                Actions.BANK_ACCOUNT_SUMMARY, Map.of("sessionToken", token)), "127.0.0.1");

        assertTrue(response.success());
        assertEquals("false", response.data().get("opened"));
        assertEquals(1, summaryCalls.get());
    }
}
