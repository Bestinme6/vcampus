package com.vcampus.client.ui;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankViewDataTest {
    @Test
    void parsesAccountAndLedgerPage() {
        ResponseMessage accountResponse = ResponseMessage.success("r1", "ok", Map.of(
                "accountId", "12", "username", "student01", "displayName", "张同学",
                "balance", "123.40", "status", "ACTIVE",
                "updatedAt", "2026-08-29T10:00:00Z"));
        BankViewData.AccountView account = BankViewData.account(accountResponse);
        assertEquals(new BigDecimal("123.40"), account.balance());
        assertEquals(BankAccountStatus.ACTIVE, account.status());

        Map<String, String> page = new LinkedHashMap<>();
        page.put("page", "1");
        page.put("pageSize", "10");
        page.put("total", "1");
        page.put("count", "1");
        page.put("row.0", RowCodec.encode("9", "12", "TRANSFER_IN", "CREDIT",
                "20.00", "123.40", "ref-1", "5", "", "收到转账",
                "2026-08-29T10:01:00Z"));
        BankViewData.LedgerPage result = BankViewData.ledgerPage(
                ResponseMessage.success("r2", "ok", page));
        assertEquals(BankLedgerType.TRANSFER_IN, result.rows().getFirst().type());
        assertEquals(BankLedgerDirection.CREDIT, result.rows().getFirst().direction());
        assertEquals(Instant.parse("2026-08-29T10:01:00Z"),
                result.rows().getFirst().createdAt());
    }

    @Test
    void adminVisibilityAndFrozenTransferFollowPolicy() {
        assertFalse(BankViewData.showAdminTabs(Set.of(UserRole.STUDENT)));
        assertTrue(BankViewData.showAdminTabs(Set.of(UserRole.BANK_ADMIN)));
        assertTrue(BankViewData.canTransfer(BankAccountStatus.ACTIVE));
        assertFalse(BankViewData.canTransfer(BankAccountStatus.FROZEN));
    }

    @Test
    void rejectsFailedOrMalformedResponses() {
        assertThrows(IllegalArgumentException.class, () -> BankViewData.account(
                ResponseMessage.failure("r", "账户不可用")));
        assertThrows(IllegalArgumentException.class, () -> BankViewData.ledgerPage(
                ResponseMessage.success("r", "ok", Map.of(
                        "page", "1", "pageSize", "10", "total", "1", "count", "1",
                        "row.0", RowCodec.encode("too", "short")))));
    }
}
