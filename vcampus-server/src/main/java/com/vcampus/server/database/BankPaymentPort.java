package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

public interface BankPaymentPort {
    AccountSnapshot paymentAccount(long userId) throws SQLException;
    LibraryPaymentResult debitForLibrary(Connection connection,long userId,BigDecimal amount,
                                          String referenceNo,String description)throws SQLException;
    LibraryPaymentResult refundForLibrary(Connection connection,long userId,BigDecimal amount,
                                           String referenceNo,String description)throws SQLException;
    ForumPaymentResult debitForForum(Connection connection,long userId,BigDecimal amount,
                                     String referenceNo,String description)throws SQLException;

    record AccountSnapshot(long accountId,BigDecimal balance,BankAccountStatus status){}
    record LibraryPaymentResult(long accountId,long ledgerId,BigDecimal balanceAfter,boolean duplicate){}
    record ForumPaymentResult(long accountId,long ledgerId,BigDecimal balanceAfter,boolean duplicate){}
}
