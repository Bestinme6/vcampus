package com.vcampus.client.fx.bank;

import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class BankDataTest {
    @Test void malformedBalanceCannotBecomeAUsableZeroBalance() {
        var response = ResponseMessage.success("r", "ok", Map.of(
                "accountId", "1", "username", "s", "displayName", "同学", "balance", "NaN",
                "status", "ACTIVE", "updatedAt", "2026-09-05T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> BankData.account(response));
        assertThrows(IllegalArgumentException.class, () -> BankData.money("1e2"));
        assertThrows(IllegalArgumentException.class, () -> BankData.money("-1.00"));
        assertEquals(new BigDecimal("12.30"), BankData.money("12.3"));
    }
    @Test void malformedPaginationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BankData.ledger(
                ResponseMessage.success("r", "", Map.of("page","1","pageSize","10","total","0","count","1",
                        "income","0.00","expense","0.00"))));
    }
    @Test void failedBusinessResponseIsDistinguishedFromBrokenData() {
        assertThrows(BankData.Rejected.class, () -> BankData.account(ResponseMessage.failure("r","余额不足")));
        assertThrows(IllegalArgumentException.class, () -> BankData.account(ResponseMessage.success("r","",Map.of())));
    }
    @Test void ledgerTotalsCanExceedOneAccountsBalanceLimit() {
        var ledger=BankData.ledger(ResponseMessage.success("r","",Map.of(
                "page","1","pageSize","10","total","0","count","0",
                "income","19999999999999.98","expense","0.00")));
        assertEquals(new BigDecimal("19999999999999.98"),ledger.income());
    }
}
