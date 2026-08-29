package com.vcampus.server.service;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.BankStore;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankServiceTest {
    private final FakeBankStore store = new FakeBankStore();
    private final SessionManager sessions = new SessionManager();
    private BankService service;
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        studentToken = sessions.create(account(
                11L, "student", "张同学", Set.of(UserRole.STUDENT))).token();
        adminToken = sessions.create(account(
                19L, "bankadmin", "银行管理员",
                Set.of(UserRole.TEACHER, UserRole.BANK_ADMIN))).token();
        service = new BankService(store, sessions);
    }

    @Test
    void transferUsesSessionIdentityAndCanonicalMoney() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.transfer(request(studentToken, Map.of(
                "senderUserId", "999", "recipientUsername", "teacher",
                "amount", "12.3", "operationId", operationId)));

        assertTrue(response.success());
        assertEquals(11L, store.lastSenderUserId);
        assertEquals("teacher", store.lastRecipientUsername);
        assertEquals(new BigDecimal("12.30"), store.lastAmount);
        assertEquals(operationId, store.lastOperationId);
        assertEquals("87.70", response.data().get("balanceAfter"));
    }

    @Test
    void malformedOperationAndMoneyNeverReachStore() {
        ResponseMessage invalidId = service.transfer(request(studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "12.30", "operationId", "bad")));
        ResponseMessage invalidMoney = service.transfer(request(studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "1e2",
                "operationId", UUID.randomUUID().toString())));

        assertFalse(invalidId.success());
        assertFalse(invalidMoney.success());
        assertEquals(0, store.transferCalls);
    }

    @Test
    void ordinaryUserCannotCallAdministrativeActions() {
        assertEquals("无权执行银行管理操作",
                service.searchAccounts(request(studentToken, Map.of())).message());
        assertEquals("无权执行银行管理操作",
                service.topUp(request(studentToken, Map.of())).message());
        assertEquals("无权执行银行管理操作",
                service.freeze(request(studentToken, Map.of())).message());
    }

    @Test
    void adminTopUpUsesSessionOperatorAndEncodesResult() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.topUp(request(adminToken, Map.of(
                "userId", "11", "amount", "50", "operationId", operationId)));

        assertTrue(response.success());
        assertEquals(19L, store.lastOperatorUserId);
        assertEquals(11L, store.lastTargetUserId);
        assertEquals("50.00", response.data().get("balanceAfter"));
    }

    @Test
    void bankRuleMessageIsSafeForClientAndLedgerRowsHaveStableOrder() {
        store.transferFailure = new BankRuleException("账户已冻结，不能转账");
        ResponseMessage rejected = service.transfer(request(studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "12.30",
                "operationId", UUID.randomUUID().toString())));
        assertEquals("账户已冻结，不能转账", rejected.message());

        ResponseMessage ledger = service.searchLedger(request(studentToken, Map.of("page", "1")));
        List<String> fields = RowCodec.decode(ledger.data().get("row.0"));
        assertEquals(List.of("31", "21", "ADMIN_TOPUP", "CREDIT", "50.00"),
                fields.subList(0, 5));
        assertEquals(11L, store.lastLedgerUserId);
    }

    @Test
    void expiredSessionIsRejected() {
        ResponseMessage response = service.account(RequestMessage.create("bank.test", Map.of()));
        assertFalse(response.success());
        assertEquals("登录已过期，请重新登录", response.message());
    }

    private RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create("bank.test", parameters);
    }

    private UserAccount account(long id, String username, String name, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", name, true, false, roles);
    }

    private static final class FakeBankStore implements BankStore {
        private long lastSenderUserId;
        private long lastOperatorUserId;
        private long lastTargetUserId;
        private long lastLedgerUserId;
        private String lastRecipientUsername;
        private String lastOperationId;
        private BigDecimal lastAmount;
        private int transferCalls;
        private BankRuleException transferFailure;

        @Override
        public BankAccountRecord account(long userId) {
            return bankAccount(userId, "student", "张同学", "100.00", BankAccountStatus.ACTIVE);
        }

        @Override
        public AccountPage searchAccounts(AccountQuery query) {
            return new AccountPage(List.of(
                    bankAccount(11L, "student", "张同学", "100.00", BankAccountStatus.ACTIVE)),
                    query.page(), query.pageSize(), 1);
        }

        @Override
        public LedgerPage searchLedger(LedgerQuery query) {
            lastLedgerUserId = query.accountUserId();
            return new LedgerPage(List.of(new BankLedgerRecord(
                    31L, 21L, BankLedgerType.ADMIN_TOPUP, BankLedgerDirection.CREDIT,
                    new BigDecimal("50.00"), new BigDecimal("50.00"), "op-1",
                    null, 19L, "管理员充值", Instant.parse("2026-08-29T10:00:00Z"))),
                    query.page(), query.pageSize(), 1);
        }

        @Override
        public TopUpResult topUp(long operatorUserId, long targetUserId,
                                 BigDecimal amount, String operationId) {
            lastOperatorUserId = operatorUserId;
            lastTargetUserId = targetUserId;
            lastAmount = amount;
            lastOperationId = operationId;
            return new TopUpResult(21L, new BigDecimal("50.00"), operationId, false);
        }

        @Override
        public StatusResult setStatus(long operatorUserId, long targetUserId,
                                      BankAccountStatus status) {
            lastOperatorUserId = operatorUserId;
            lastTargetUserId = targetUserId;
            return new StatusResult(21L, status, true);
        }

        @Override
        public TransferResult transfer(long senderUserId, String recipientUsername,
                                       BigDecimal amount, String operationId) throws SQLException {
            transferCalls++;
            if (transferFailure != null) throw transferFailure;
            lastSenderUserId = senderUserId;
            lastRecipientUsername = recipientUsername;
            lastAmount = amount;
            lastOperationId = operationId;
            return new TransferResult(operationId, new BigDecimal("87.70"),
                    new BigDecimal("12.30"), false);
        }

        private BankAccountRecord bankAccount(long userId, String username, String name,
                                                String balance, BankAccountStatus status) {
            Instant now = Instant.parse("2026-08-29T10:00:00Z");
            return new BankAccountRecord(21L, userId, username, name,
                    new BigDecimal(balance), status, now, now);
        }
    }
}
