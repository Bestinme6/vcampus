package com.vcampus.server.model;

import com.vcampus.common.model.BankAccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record BankAccountRecord(
        long id,
        long userId,
        String username,
        String displayName,
        BigDecimal balance,
        BankAccountStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
