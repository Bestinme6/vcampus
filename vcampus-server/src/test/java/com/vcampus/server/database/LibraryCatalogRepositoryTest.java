package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCopyStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.LibraryCatalogStore.BookCommand;
import com.vcampus.server.database.LibraryCatalogStore.CatalogPage;
import com.vcampus.server.database.LibraryCatalogStore.CatalogQuery;
import com.vcampus.server.database.LibraryCatalogStore.CopyQuery;
import com.vcampus.server.database.LibraryCatalogStore.CreateCopy;
import com.vcampus.server.database.LibraryCatalogStore.CreatedBook;
import com.vcampus.server.database.LibraryCatalogStore.CreatedCopy;
import com.vcampus.server.database.LibraryCatalogStore.MutationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryCatalogRepositoryTest {
    private ConnectionFactory connections;
    private LibraryCatalogRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        seedCatalog();
        repository = new LibraryCatalogRepository(connections);
    }

    @Test
    void searchReturnsAggregatedCopyCountsAndEscapesWildcards() throws SQLException {
        CatalogPage page = repository.search(new CatalogQuery("100%", "", 1, 10));

        assertEquals(1, page.total());
        assertEquals(1, page.rows().size());
        assertEquals(4, page.rows().getFirst().totalCopies());
        assertEquals(2, page.rows().getFirst().availableCopies());
    }

    @Test
    void createsFindsAndUpdatesBooksUsingNormalizedIsbn() throws SQLException {
        CreatedBook created = repository.createBook(new BookCommand(
                "0-306-40615-2", "数据库系统", "作者", "出版社", 2024, "计算机", "简介"));
        long bookId = created.bookId();

        assertEquals("BK000000003", created.catalogCode());
        assertEquals("0306406152", repository.findBook(bookId).orElseThrow().isbn());
        assertThrows(SQLException.class, () -> repository.createBook(new BookCommand(
                "0306406152", "重复", "作者", "出版社", 2024, "计算机", null)));
        assertEquals(MutationResult.CHANGED, repository.updateBook(bookId, new BookCommand(
                "0-306-40615-2", "数据库原理", "新作者", "新出版社", 2025, "计算机", null)));
        assertEquals("数据库原理", repository.findBook(bookId).orElseThrow().title());
        assertEquals(MutationResult.NOT_FOUND, repository.updateBook(9999L, new BookCommand(
                "0-306-40615-2", "不存在", "作者", "出版社", 2025, "计算机", null)));
    }

    @Test
    void createsBookWithoutIsbnUsingGeneratedCatalogCode() throws SQLException {
        CreatedBook created = repository.createBook(new BookCommand(
                "", "校内讲义", "教务处", "校内", 2026, "讲义", null));

        var saved = repository.findBook(created.bookId()).orElseThrow();
        assertEquals("BK000000003", created.catalogCode());
        assertNull(saved.isbn());
    }

    @Test
    void concurrentBookCreationAllocatesDistinctCatalogCodes() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return repository.createBook(new BookCommand(
                        "", "校内讲义一", "教务处", "校内", 2026, "讲义", null));
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.createBook(new BookCommand(
                        "", "校内讲义二", "教务处", "校内", 2026, "讲义", null));
            });
            start.countDown();

            assertEquals(Set.of("BK000000003", "BK000000004"),
                    Set.of(first.get().catalogCode(), second.get().catalogCode()));
        }
    }

    @Test
    void createsAndFindsCopiesByNormalizedBarcode() throws SQLException {
        CreatedCopy created = repository.createCopy(new CreateCopy(1L, "", "A-02"));
        long copyId = created.copyId();

        assertEquals("B000000130", created.barcode());
        assertEquals("B000000130", repository.searchCopies(
                new CopyQuery(null, "B000000130", null, 1, 10)).rows().getFirst().barcode());
        assertThrows(SQLException.class,
                () -> repository.createCopy(new CreateCopy(1L, "B000000130", "A-03")));
        assertEquals(copyId, repository.searchCopies(
                new CopyQuery(1L, "0000130", LibraryCopyStatus.AVAILABLE, 1, 10))
                .rows().getFirst().copyId());
    }

    @Test
    void concurrentCopyCreationAllocatesDistinctBarcodes() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return repository.createCopy(new CreateCopy(1L, "", "A-02"));
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.createCopy(new CreateCopy(1L, "", "A-03"));
            });
            start.countDown();

            assertEquals(Set.of("B000000130", "B000000131"),
                    Set.of(first.get().barcode(), second.get().barcode()));
        }
    }

    @Test
    void explicitImportedBarcodeAdvancesAutomaticSequence() throws SQLException {
        repository.createCopy(new CreateCopy(1L, "B000000200", "A-04"));

        CreatedCopy automatic = repository.createCopy(new CreateCopy(1L, "", "A-05"));

        assertEquals("B000000201", automatic.barcode());
    }

    @Test
    void copyStatusRejectsCirculationConflictsAndRequiresReasons() throws SQLException {
        assertEquals(MutationResult.CONFLICT,
                repository.setCopyStatus(3L, LibraryCopyStatus.AVAILABLE, null));
        assertEquals(MutationResult.CONFLICT,
                repository.setCopyStatus(1L, LibraryCopyStatus.ON_LOAN, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.setCopyStatus(1L, LibraryCopyStatus.DAMAGED, " "));

        assertEquals(MutationResult.CHANGED,
                repository.setCopyStatus(1L, LibraryCopyStatus.DAMAGED, "书页破损"));
        assertEquals("书页破损", scalarString("SELECT status_reason FROM book_copies WHERE id = 1"));
        assertEquals(MutationResult.CHANGED,
                repository.setCopyStatus(1L, LibraryCopyStatus.AVAILABLE, "忽略"));
        assertNull(scalarString("SELECT status_reason FROM book_copies WHERE id = 1"));
        assertEquals(MutationResult.NOT_FOUND,
                repository.setCopyStatus(9999L, LibraryCopyStatus.LOST, "遗失"));
    }

    @Test
    void disablesBookWithoutDeletingCatalogHistory() throws SQLException {
        assertEquals(MutationResult.CHANGED, repository.setBookEnabled(1L, false));
        assertFalse(repository.findBook(1L).orElseThrow().enabled());
        assertEquals(0, repository.search(new CatalogQuery("100%", "", 1, 10)).total());
        CatalogPage management = repository.search(
                new CatalogQuery("", "", true, true, 1, 10));
        assertEquals(2L, management.rows().getFirst().bookId());
        assertEquals(2, management.total());
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM books WHERE id = 1"));
        assertEquals(MutationResult.UNCHANGED, repository.setBookEnabled(1L, false));
        assertEquals(MutationResult.NOT_FOUND, repository.setBookEnabled(9999L, false));
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE library_code_sequences (code_type VARCHAR(32) PRIMARY KEY, next_value BIGINT NOT NULL)");
            statement.execute("CREATE TABLE books (id BIGINT AUTO_INCREMENT PRIMARY KEY, catalog_code CHAR(11) UNIQUE NOT NULL, isbn VARCHAR(20) UNIQUE, title VARCHAR(200) NOT NULL, authors VARCHAR(300) NOT NULL, publisher VARCHAR(160) NOT NULL, publish_year SMALLINT, category VARCHAR(80) NOT NULL, description VARCHAR(1000), enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE book_copies (id BIGINT AUTO_INCREMENT PRIMARY KEY, book_id BIGINT NOT NULL, barcode CHAR(10) UNIQUE NOT NULL, shelf_location VARCHAR(80) NOT NULL, status VARCHAR(16) NOT NULL, status_reason VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (book_id) REFERENCES books(id))");
            statement.execute("CREATE TABLE library_loans (id BIGINT AUTO_INCREMENT PRIMARY KEY, copy_id BIGINT NOT NULL, returned_at TIMESTAMP, FOREIGN KEY (copy_id) REFERENCES book_copies(id))");
        }
    }

    private void seedCatalog() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO library_code_sequences VALUES ('BOOK_CATALOG', 3), ('COPY_BARCODE', 130)");
            statement.executeUpdate("INSERT INTO books (id, catalog_code, isbn, title, authors, publisher, publish_year, category, description, enabled) VALUES (1, 'BK000000001', '9787111565277', '100% Java', 'Brian Goetz', '机械工业出版社', 2020, '计算机', '并发编程', TRUE)");
            statement.executeUpdate("INSERT INTO books (id, catalog_code, isbn, title, authors, publisher, publish_year, category, description, enabled) VALUES (2, 'BK000000002', '9780134685991', '100X Java', 'Joshua Bloch', 'Addison-Wesley', 2018, '计算机', NULL, TRUE)");
            statement.executeUpdate("INSERT INTO book_copies (id, book_id, barcode, shelf_location, status, status_reason) VALUES (1, 1, 'B000000126', 'A-01', 'AVAILABLE', NULL), (2, 1, 'B000000127', 'A-01', 'AVAILABLE', NULL), (3, 1, 'B000000128', 'A-01', 'ON_LOAN', NULL), (4, 1, 'B000000129', 'A-01', 'DAMAGED', '封面破损')");
            statement.executeUpdate("INSERT INTO library_loans (copy_id, returned_at) VALUES (3, NULL)");
        }
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String scalarString(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
