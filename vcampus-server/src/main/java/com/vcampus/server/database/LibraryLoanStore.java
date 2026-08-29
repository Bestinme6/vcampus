package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCirculationOperation;
import com.vcampus.common.model.LibraryCopyStatus;
import com.vcampus.common.model.LibraryLoanChannel;
import com.vcampus.common.model.LibraryReturnCondition;
import com.vcampus.common.model.UserRole;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LibraryLoanStore {
    Optional<Borrower> findBorrower(String username) throws SQLException;

    CirculationPreview previewCirculation(long borrowerUserId, String barcode,
                                          LibraryCirculationOperation operation, Instant now,
                                          int maxActiveLoans) throws SQLException;

    LoanPage searchBorrowerLoans(long borrowerUserId, LoanQuery query) throws SQLException;

    LoanPage searchAllLoans(LoanQuery query) throws SQLException;

    BorrowReceipt borrow(BorrowCommand command) throws SQLException;

    ReturnReceipt returnLoan(ReturnCommand command) throws SQLException;

    RenewReceipt renew(RenewCommand command) throws SQLException;

    record Borrower(long userId, String username, String displayName,
                    UserRole baseIdentity, boolean enabled) {
    }

    record CirculationPreview(long copyId, long bookId, String title, String barcode,
                              LibraryCopyStatus copyStatus, Long activeLoanId,
                              int activeLoans, int maxLoans, boolean overdue,
                              boolean allowed, String message) {
    }

    record LoanQuery(String keyword, Boolean active, Boolean overdue, int page, int pageSize) {
    }

    record LoanRecord(long loanId, long bookId, String isbn, String title, long copyId,
                      String barcode, String borrowerUsername, String borrowerDisplayName,
                      Instant borrowedAt, Instant dueAt, int renewalCount, Instant returnedAt,
                      LibraryReturnCondition returnCondition, LibraryLoanChannel channel,
                      boolean overdue, boolean renewable) {
    }

    record LoanPage(List<LoanRecord> rows, int page, int pageSize, int total) {
        public LoanPage {
            rows = List.copyOf(rows);
        }
    }

    record BorrowCommand(long borrowerUserId, Long bookId, String barcode,
                         long operatorUserId, LibraryLoanChannel channel, Instant borrowedAt,
                         Instant dueAt, int maxActiveLoans) {
    }

    record ReturnCommand(long borrowerUserId, Long loanId, String barcode,
                         long operatorUserId, LibraryReturnCondition condition, String reason,
                         Instant returnedAt, boolean administrator) {
    }

    record RenewCommand(long borrowerUserId, long loanId, Instant now, Duration extension) {
    }

    record BorrowReceipt(long loanId, long copyId, String barcode, String title, Instant dueAt) {
    }

    record ReturnReceipt(long loanId, long copyId, String barcode,
                         LibraryReturnCondition condition, Instant returnedAt) {
    }

    record RenewReceipt(long loanId, Instant dueAt, int renewalCount) {
    }
}
