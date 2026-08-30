package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCopyStatus;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LibraryCatalogStore {
    CatalogPage search(CatalogQuery query) throws SQLException;

    Optional<CatalogItem> findBook(long bookId) throws SQLException;

    CreatedBook createBook(BookCommand command) throws SQLException;

    MutationResult updateBook(long bookId, BookCommand command) throws SQLException;

    MutationResult setBookEnabled(long bookId, boolean enabled) throws SQLException;

    CopyPage searchCopies(CopyQuery query) throws SQLException;

    CreatedCopy createCopy(CreateCopy command) throws SQLException;

    MutationResult setCopyStatus(long copyId, LibraryCopyStatus status, String reason)
            throws SQLException;

    enum MutationResult {
        NOT_FOUND,
        UNCHANGED,
        CHANGED,
        CONFLICT
    }

    record CatalogQuery(String keyword, String category, boolean includeDisabled,
                        boolean newestFirst, int page, int pageSize) {
        public CatalogQuery(String keyword, String category, int page, int pageSize) {
            this(keyword, category, false, false, page, pageSize);
        }
    }

    record CatalogItem(long bookId, String catalogCode, String isbn, String title, String authors,
                       String publisher, Integer publishYear, String category, String description,
                       boolean enabled, int totalCopies, int availableCopies) {
    }

    record CatalogPage(List<CatalogItem> rows, int page, int pageSize, int total) {
        public CatalogPage {
            rows = List.copyOf(rows);
        }
    }

    record BookCommand(String isbn, String title, String authors, String publisher,
                       Integer publishYear, String category, String description) {
    }

    record CreatedBook(long bookId, String catalogCode) {
    }

    record CopyQuery(Long bookId, String keyword, LibraryCopyStatus status,
                     boolean newestFirst, int page, int pageSize) {
        public CopyQuery(Long bookId, String keyword, LibraryCopyStatus status,
                         int page, int pageSize) {
            this(bookId, keyword, status, false, page, pageSize);
        }
    }

    record CopyRecord(long copyId, long bookId, String barcode, String title,
                      String shelfLocation, LibraryCopyStatus status, String statusReason,
                      Instant updatedAt) {
    }

    record CopyPage(List<CopyRecord> rows, int page, int pageSize, int total) {
        public CopyPage {
            rows = List.copyOf(rows);
        }
    }

    record CreateCopy(long bookId, String barcode, String shelfLocation) {
    }

    record CreatedCopy(long copyId, String barcode) {
    }
}
