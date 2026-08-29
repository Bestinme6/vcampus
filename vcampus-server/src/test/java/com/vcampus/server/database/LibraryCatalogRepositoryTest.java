package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCopyStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.LibraryCatalogStore.BookCommand;
import com.vcampus.server.database.LibraryCatalogStore.CatalogPage;
import com.vcampus.server.database.LibraryCatalogStore.CatalogQuery;
import com.vcampus.server.database.LibraryCatalogStore.CopyQuery;
import com.vcampus.server.database.LibraryCatalogStore.CreateCopy;
import com.vcampus.server.database.LibraryCatalogStore.MutationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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
        long bookId = repository.createBook(new BookCommand(
                "0-306-40615-2", "数据库系统", "作者", "出版社", 2024, "计算机", "简介"));

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
    void createsAndFindsCopiesByNormalizedBarcode() throws SQLException {
        long copyId = repository.createCopy(new CreateCopy(1L, " B000000130 ", "A-02"));

        assertEquals("B000000130", repository.searchCopies(
                new CopyQuery(null, "B000000130", null, 1, 10)).rows().getFirst().barcode());
        assertThrows(SQLException.class,
                () -> repository.createCopy(new CreateCopy(1L, "B000000130", "A-03")));
        assertEquals(copyId, repository.searchCopies(
                new CopyQuery(1L, "0000130", LibraryCopyStatus.AVAILABLE, 1, 10))
                .rows().getFirst().copyId());
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
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM books WHERE id = 1"));
        assertEquals(MutationResult.UNCHANGED, repository.setBookEnabled(1L, false));
        assertEquals(MutationResult.NOT_FOUND, repository.setBookEnabled(9999L, false));
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE books (id BIGINT AUTO_INCREMENT PRIMARY KEY, isbn VARCHAR(20) UNIQUE NOT NULL, title VARCHAR(200) NOT NULL, authors VARCHAR(300) NOT NULL, publisher VARCHAR(160) NOT NULL, publish_year SMALLINT, category VARCHAR(80) NOT NULL, description VARCHAR(1000), enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE book_copies (id BIGINT AUTO_INCREMENT PRIMARY KEY, book_id BIGINT NOT NULL, barcode CHAR(10) UNIQUE NOT NULL, shelf_location VARCHAR(80) NOT NULL, status VARCHAR(16) NOT NULL, status_reason VARCHAR(255), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (book_id) REFERENCES books(id))");
            statement.execute("CREATE TABLE library_loans (id BIGINT AUTO_INCREMENT PRIMARY KEY, copy_id BIGINT NOT NULL, returned_at TIMESTAMP, FOREIGN KEY (copy_id) REFERENCES book_copies(id))");
        }
    }

    private void seedCatalog() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO books (id, isbn, title, authors, publisher, publish_year, category, description, enabled) VALUES (1, '9787111565277', '100% Java', 'Brian Goetz', '机械工业出版社', 2020, '计算机', '并发编程', TRUE)");
            statement.executeUpdate("INSERT INTO books (id, isbn, title, authors, publisher, publish_year, category, description, enabled) VALUES (2, '9780134685991', '100X Java', 'Joshua Bloch', 'Addison-Wesley', 2018, '计算机', NULL, TRUE)");
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
