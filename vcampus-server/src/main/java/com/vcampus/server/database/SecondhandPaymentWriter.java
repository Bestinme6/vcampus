package com.vcampus.server.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

public interface SecondhandPaymentWriter {
    BankPaymentWriter.PaymentResult hold(
            Connection connection, long buyerId, BigDecimal amount, String referenceNo)
            throws SQLException;

    BankPaymentWriter.PaymentResult refund(
            Connection connection, long buyerId, BigDecimal amount, String referenceNo)
            throws SQLException;

    BankPaymentWriter.PaymentResult payout(
            Connection connection, long sellerId, BigDecimal amount, String referenceNo)
            throws SQLException;
}
