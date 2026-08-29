package com.vcampus.server.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

public interface BankPaymentWriter {
    PaymentResult debitForShop(
            Connection connection,
            long userId,
            BigDecimal amount,
            String referenceNo,
            String description) throws SQLException;

    PaymentResult refundForShop(
            Connection connection,
            long userId,
            BigDecimal amount,
            String referenceNo,
            String description) throws SQLException;

    record PaymentResult(long accountId, BigDecimal balanceAfter, boolean duplicate) {
    }
}
