package com.vcampus.server.service;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.database.BankRuleException;
import com.vcampus.server.database.BankStore;
import com.vcampus.server.model.BankAccountRecord;
import com.vcampus.server.model.BankLedgerRecord;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.support.RecordingAuditStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankServiceTest {
    private final FakeBankStore store = new FakeBankStore();
    private final SessionManager sessions = new SessionManager();
    private RecordingAuditStore audit;
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
        audit = new RecordingAuditStore();
        service = new BankService(store, sessions, audit);
    }

    @Test
    void transferUsesSessionIdentityAndCanonicalMoney() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.transfer(request(Actions.BANK_TRANSFER_CREATE, studentToken, Map.of(
                "senderUserId", "999", "recipientUsername", "teacher",
                "amount", "12.3", "operationId", operationId)));

        assertTrue(response.success());
        assertEquals(11L, store.lastSenderUserId);
        assertEquals("teacher", store.lastRecipientUsername);
        assertEquals(new BigDecimal("12.30"), store.lastAmount);
        assertEquals(operationId, store.lastOperationId);
        assertEquals("87.70", response.data().get("balanceAfter"));
        assertEquals(new RecordingAuditStore.Entry(
                11L, Actions.BANK_TRANSFER_CREATE, "SUCCESS", null), audit.last());
    }

    @Test
    void malformedOperationAndMoneyNeverReachStore() {
        ResponseMessage invalidId = service.transfer(request(Actions.BANK_TRANSFER_CREATE, studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "12.30", "operationId", "bad")));
        ResponseMessage invalidMoney = service.transfer(request(Actions.BANK_TRANSFER_CREATE, studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "1e2",
                "operationId", UUID.randomUUID().toString())));

        assertFalse(invalidId.success());
        assertFalse(invalidMoney.success());
        assertEquals(0, store.transferCalls);
        assertEquals(2, audit.entries().size());
        assertEquals("INVALID", audit.last().result());
    }

    @Test
    void ordinaryUserCannotCallAdministrativeActions() {
        assertEquals("无权执行银行管理操作",
                service.searchAccounts(request(studentToken, Map.of())).message());
        assertEquals("无权执行银行管理操作",
                service.topUp(request(Actions.BANK_ADMIN_TOPUP, studentToken, Map.of())).message());
        assertEquals(new RecordingAuditStore.Entry(
                11L, Actions.BANK_ADMIN_TOPUP, "DENIED", null), audit.last());
        assertEquals("无权执行银行管理操作",
                service.freeze(request(Actions.BANK_ADMIN_FREEZE, studentToken, Map.of())).message());
    }

    @Test
    void adminTopUpUsesSessionOperatorAndEncodesResult() {
        String operationId = UUID.randomUUID().toString();
        ResponseMessage response = service.topUp(request(Actions.BANK_ADMIN_TOPUP, adminToken, Map.of(
                "targetUsername", "student", "amount", "50", "operationId", operationId)));

        assertTrue(response.success());
        assertEquals(19L, store.lastOperatorUserId);
        assertEquals("student", store.lastTargetUsername);
        assertEquals("50.00", response.data().get("balanceAfter"));
        assertEquals("SUCCESS", audit.last().result());
    }

    @Test
    void adminTopUpRequiresTargetUsername() {
        ResponseMessage response = service.topUp(request(Actions.BANK_ADMIN_TOPUP, adminToken, Map.of(
                "amount", "50", "operationId", UUID.randomUUID().toString())));

        assertFalse(response.success());
        assertEquals("请填写目标用户名或学号", response.message());
        assertEquals("INVALID", audit.last().result());
    }

    @Test
    void bankRuleMessageIsSafeForClientAndLedgerRowsHaveStableOrder() {
        store.transferFailure = new BankRuleException("账户已冻结，不能转账");
        ResponseMessage rejected = service.transfer(request(Actions.BANK_TRANSFER_CREATE, studentToken, Map.of(
                "recipientUsername", "teacher", "amount", "12.30",
                "operationId", UUID.randomUUID().toString())));
        assertEquals("账户已冻结，不能转账", rejected.message());
        assertEquals("CONFLICT", audit.last().result());

        ResponseMessage ledger = service.searchLedger(request(studentToken, Map.of("page", "1")));
        List<String> fields = RowCodec.decode(ledger.data().get("row.0"));
        assertEquals(List.of("31", "21", "ADMIN_TOPUP", "CREDIT", "50.00"),
                fields.subList(0, 5));
        assertEquals("student", store.lastLedgerUsername);
    }

    @Test
    void administratorFiltersLedgerByUsernameOrRequestsAllRows() {
        ResponseMessage filtered = service.searchLedger(request(adminToken, Map.of(
                "targetUsername", "student", "page", "1")));
        assertTrue(filtered.success());
        assertEquals("student", store.lastLedgerUsername);

        ResponseMessage response = service.searchLedger(request(adminToken, Map.of("page", "1")));

        assertTrue(response.success());
        assertEquals(null, store.lastLedgerUsername);
    }

    @Test
    void administratorChangesStatusByUsername() {
        ResponseMessage response = service.freeze(request(Actions.BANK_ADMIN_FREEZE, adminToken, Map.of(
                "targetUsername", "student")));

        assertTrue(response.success());
        assertEquals(19L, store.lastOperatorUserId);
        assertEquals("student", store.lastTargetUsername);
        assertEquals(Actions.BANK_ADMIN_FREEZE, audit.last().action());
        assertEquals("SUCCESS", audit.last().result());
    }

    @Test
    void expiredSessionIsRejected() {
        ResponseMessage response = service.account(RequestMessage.create("bank.test", Map.of()));
        assertFalse(response.success());
        assertEquals("登录已过期，请重新登录", response.message());
    }

    @Test
    void accountSummaryDoesNotOpenAnAccountWhenNoneExists() {
        store.summary = Optional.empty();

        ResponseMessage response = service.accountSummary(request(studentToken, Map.of()));

        assertTrue(response.success());
        assertEquals("false", response.data().get("opened"));
        assertEquals(1, store.summaryCalls);
        assertEquals(0, store.accountCalls);
    }

    @Test
    void personalScopeCannotExpandEvenForAnAdministrator() {
        assertTrue(service.searchLedger(request(adminToken,
                Map.of("scope", "mine", "targetUsername", "student"))).success());
        assertEquals("bankadmin", store.lastLedgerUsername);
        assertTrue(service.searchLedger(request(studentToken,
                Map.of("targetUsername", "bankadmin"))).success());
        assertEquals("student", store.lastLedgerUsername);
    }

    @Test
    void invalidDatesAreRejectedBeforeQuerying() {
        assertFalse(service.searchLedger(request(studentToken,
                Map.of("fromDate", "2026-09-06", "toDate", "2026-09-05"))).success());
        assertFalse(service.searchLedger(request(studentToken,
                Map.of("fromDate", "invalid"))).success());
    }
    @Test
    void recipientLookupDoesNotExposePrivateBalanceAndFlagsSelf() {
        var response=service.recipient(request(studentToken,Map.of("recipientUsername","student")));
        assertTrue(response.success());
        assertEquals("true",response.data().get("self"));
        assertFalse(response.data().containsKey("balance"));
        assertFalse(response.data().containsKey("userId"));
        assertEquals(0,store.accountCalls);
        assertFalse(service.recipient(RequestMessage.create("bank.recipient.get",Map.of())).success());
    }

    @Test
    void databaseFailureIsAuditedAndReadOnlyLedgerSearchIsNot() {
        store.topUpFailure = new SQLException("offline");

        ResponseMessage failed = service.topUp(request(Actions.BANK_ADMIN_TOPUP, adminToken, Map.of(
                "targetUsername", "student", "amount", "50",
                "operationId", UUID.randomUUID().toString())));

        assertFalse(failed.success());
        assertEquals("DATABASE_ERROR", audit.last().result());

        audit = new RecordingAuditStore();
        service = new BankService(store, sessions, audit);
        assertTrue(service.searchLedger(request(Actions.BANK_LEDGER_SEARCH, studentToken,
                Map.of("page", "1"))).success());
        assertTrue(audit.entries().isEmpty());
    }

    private RequestMessage request(String token, Map<String, String> values) {
        return request("bank.test", token, values);
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private UserAccount account(long id, String username, String name, Set<UserRole> roles) {
        return new UserAccount(id, username, "hash", "salt", name, true, false, roles);
    }

    private static final class FakeBankStore implements BankStore {
        @Override public Recipient recipient(String username) {
            return new Recipient(11,"student","张同学");
        }
        private long lastSenderUserId;
        private long lastOperatorUserId;
        private String lastTargetUsername;
        private String lastLedgerUsername;
        private String lastRecipientUsername;
        private String lastOperationId;
        private BigDecimal lastAmount;
        private int transferCalls;
        private int accountCalls;
        private int summaryCalls;
        private BankRuleException transferFailure;
        private SQLException topUpFailure;
        private Optional<BankAccountRecord> summary = Optional.of(
                bankAccount(11L, "student", "张同学", "100.00", BankAccountStatus.ACTIVE));

        @Override
        public BankAccountRecord account(long userId) {
            accountCalls++;
            return bankAccount(userId, "student", "张同学", "100.00", BankAccountStatus.ACTIVE);
        }

        @Override
        public Optional<BankAccountRecord> accountSummary(long userId) {
            summaryCalls++;
            return summary;
        }

        @Override
        public AccountPage searchAccounts(AccountQuery query) {
            return new AccountPage(List.of(
                    bankAccount(11L, "student", "张同学", "100.00", BankAccountStatus.ACTIVE)),
                    query.page(), query.pageSize(), 1);
        }

        @Override
        public LedgerPage searchLedger(LedgerQuery query) {
            lastLedgerUsername = query.accountUsername();
            return new LedgerPage(List.of(new BankLedgerRecord(
                    31L, 21L, BankLedgerType.ADMIN_TOPUP, BankLedgerDirection.CREDIT,
                    new BigDecimal("50.00"), new BigDecimal("50.00"), "op-1",
                    null, 19L, "管理员充值", Instant.parse("2026-08-29T10:00:00Z"))),
                    query.page(), query.pageSize(), 1);
        }

        @Override
        public TopUpResult topUp(long operatorUserId, String targetUsername,
                                 BigDecimal amount, String operationId) throws SQLException {
            if (topUpFailure != null) throw topUpFailure;
            lastOperatorUserId = operatorUserId;
            lastTargetUsername = targetUsername;
            lastAmount = amount;
            lastOperationId = operationId;
            return new TopUpResult(21L, new BigDecimal("50.00"), operationId, false);
        }

        @Override
        public StatusResult setStatus(long operatorUserId, String targetUsername,
                                      BankAccountStatus status) {
            lastOperatorUserId = operatorUserId;
            lastTargetUsername = targetUsername;
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
