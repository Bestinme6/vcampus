package com.vcampus.server.database;

import com.vcampus.common.model.LibraryDamageLevel;
import com.vcampus.common.model.LibraryReturnCondition;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public interface LibraryFeeWriter {
    LibraryFeeWriter NONE = new LibraryFeeWriter() {
        public boolean hasPending(Connection connection, long borrowerUserId) { return false; }
        public Optional<CreatedBill> createForReturn(Connection connection, ClosedLoan loan) {
            return Optional.empty();
        }
    };

    boolean hasPending(Connection connection, long borrowerUserId) throws SQLException;
    Optional<CreatedBill> createForReturn(Connection connection, ClosedLoan loan) throws SQLException;

    record ClosedLoan(long loanId, long borrowerUserId, long bookId, long copyId,
                      String title, String barcode, Instant dueAt, Instant closedAt,
                      LibraryReturnCondition condition, LibraryDamageLevel damageLevel,
                      String reason, long operatorUserId) { }
    record CreatedBill(long billId, String billNo, BigDecimal totalAmount) { }
}
