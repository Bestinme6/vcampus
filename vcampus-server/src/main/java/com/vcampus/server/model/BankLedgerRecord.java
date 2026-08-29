package com.vcampus.server.model;

import com.vcampus.common.model.BankLedgerDirection;
import com.vcampus.common.model.BankLedgerType;

import java.math.BigDecimal;
import java.time.Instant;

public record BankLedgerRecord(
        long id,
        long accountId,
        BankLedgerType type,
        BankLedgerDirection direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String referenceNo,
        Long counterpartyUserId,
        Long operatorUserId,
        String description,
        Instant createdAt) {
}
