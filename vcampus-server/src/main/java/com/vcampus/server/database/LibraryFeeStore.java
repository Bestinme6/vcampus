package com.vcampus.server.database;

import com.vcampus.common.model.*;
import com.vcampus.common.model.BankAccountStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LibraryFeeStore extends LibraryFeeWriter {
    LibraryFeePolicy currentPolicy() throws SQLException;
    LibraryFeePolicy updatePolicy(long operatorUserId, LibraryFeePolicy policy) throws SQLException;
    LibraryFeeAssessment previewReturn(ReturnFeePreview command) throws SQLException;
    FeeBillPage searchOwner(long userId, FeeBillQuery query) throws SQLException;
    FeeBillPage searchAll(FeeBillQuery query) throws SQLException;
    Optional<FeeBill> findForOwner(long userId, long billId) throws SQLException;
    Optional<FeeBill> find(long billId) throws SQLException;
    PaymentPreview paymentPreview(long userId,long billId)throws SQLException;
    PaymentReceipt pay(long userId,long billId,String operationId,Instant now)throws SQLException;
    FeeBill waive(long operatorUserId,long billId,String reason,Instant now)throws SQLException;
    PaymentReceipt refund(long operatorUserId,long billId,String reason,String operationId,Instant now)throws SQLException;

    record ReturnFeePreview(String barcode, Instant closedAt,
                            LibraryReturnCondition condition, LibraryDamageLevel damageLevel) { }
    record FeeBillQuery(String keyword, LibraryFeeStatus status, int page, int pageSize) { }
    record FeeBill(long billId, String billNo, long loanId, long borrowerUserId,
                   String borrowerUsername, String borrowerDisplayName, long bookId, long copyId,
                   String title, String barcode, LibraryFeeStatus status,
                   LibraryReturnCondition returnCondition, LibraryDamageLevel damageLevel,
                   Instant dueAt, Instant closedAt, int overdueDays, int billableDays,
                   BigDecimal replacementPriceSnapshot, int graceDaysSnapshot,
                   BigDecimal dailyOverdueFeeSnapshot, BigDecimal overdueCapSnapshot,
                   BigDecimal conditionRateSnapshot, BigDecimal overdueAmount,
                   BigDecimal damageAmount, BigDecimal lostAmount, BigDecimal totalAmount,
                   String reason, String paymentReferenceNo, Instant paidAt,
                   String waivedReason, Instant waivedAt, String refundReferenceNo,
                   String refundedReason, Instant refundedAt, Instant createdAt) { }
    record FeeBillPage(List<FeeBill> rows, int page, int pageSize, int total) {
        public FeeBillPage { rows = List.copyOf(rows); }
    }
    record PaymentPreview(FeeBill bill,BigDecimal balance,BankAccountStatus accountStatus){}
    record PaymentReceipt(FeeBill bill,long ledgerId,BigDecimal balanceAfter,boolean duplicate){}
}
