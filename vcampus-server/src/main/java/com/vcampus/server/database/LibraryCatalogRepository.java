package com.vcampus.server.database;

import com.vcampus.common.model.LibraryCodePolicy;
import com.vcampus.common.model.LibraryCopyStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class LibraryCatalogRepository implements LibraryCatalogStore {
    private final ConnectionFactory connectionFactory;

    public LibraryCatalogRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public CatalogPage search(CatalogQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String keyword = trim(query.keyword());
        String category = trim(query.category());
        int page = Math.max(1, query.page());
        int pageSize = Math.max(1, Math.min(100, query.pageSize()));
        try (Connection connection = connectionFactory.openConnection()) {
            int total = countBooks(connection, keyword, category, query.includeDisabled());
            List<CatalogItem> rows = queryBooks(connection, keyword, category,
                    query.includeDisabled(), query.newestFirst(), page, pageSize);
            return new CatalogPage(rows, page, pageSize, total);
        }
    }

    @Override
    public Optional<CatalogItem> findBook(long bookId) throws SQLException {
        String sql = catalogSelect() + " WHERE b.id = ? " + catalogGroupBy();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, bookId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readCatalogItem(result)) : Optional.empty();
            }
        }
    }

    @Override
    public CreatedBook createBook(BookCommand command) throws SQLException {
        BookCommand normalized = normalize(command);
        String sql = """
                INSERT INTO books
                    (catalog_code, isbn, title, authors, publisher, publish_year, category, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String catalogCode = formatCode("BK", nextCode(connection, "BOOK_CATALOG"));
                try (PreparedStatement statement = connection.prepareStatement(
                        sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, catalogCode);
                    bindBook(statement, normalized, 2);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("数据库未返回书目ID");
                        }
                        CreatedBook created = new CreatedBook(keys.getLong(1), catalogCode);
                        connection.commit();
                        return created;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public MutationResult updateBook(long bookId, BookCommand command) throws SQLException {
        BookCommand normalized = normalize(command);
        String sql = """
                UPDATE books
                   SET isbn = ?, title = ?, authors = ?, publisher = ?, publish_year = ?,
                       category = ?, description = ?
                 WHERE id = ?
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindBook(statement, normalized, 1);
            statement.setLong(8, bookId);
            int affected = statement.executeUpdate();
            if (affected > 0) {
                return MutationResult.CHANGED;
            }
            return exists(connection, "books", bookId)
                    ? MutationResult.UNCHANGED : MutationResult.NOT_FOUND;
        }
    }

    @Override
    public MutationResult setBookEnabled(long bookId, boolean enabled) throws SQLException {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE books SET enabled = ? WHERE id = ? AND enabled <> ?")) {
            statement.setBoolean(1, enabled);
            statement.setLong(2, bookId);
            statement.setBoolean(3, enabled);
            if (statement.executeUpdate() > 0) {
                return MutationResult.CHANGED;
            }
            return exists(connection, "books", bookId)
                    ? MutationResult.UNCHANGED : MutationResult.NOT_FOUND;
        }
    }

    @Override
    public CopyPage searchCopies(CopyQuery query) throws SQLException {
        Objects.requireNonNull(query, "query");
        String keyword = trim(query.keyword());
        int page = Math.max(1, query.page());
        int pageSize = Math.max(1, Math.min(100, query.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = appendCopyFilters(where, query.bookId(), keyword, query.status());
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM book_copies c JOIN books b ON b.id = c.book_id" + where)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<CopyRecord> rows = new ArrayList<>();
            String sql = """
                    SELECT c.id, c.book_id, c.barcode, b.title, c.shelf_location,
                           c.status, c.status_reason, c.updated_at
                      FROM book_copies c
                      JOIN books b ON b.id = c.book_id
                    """ + where + (query.newestFirst()
                            ? " ORDER BY c.id DESC" : " ORDER BY c.barcode, c.id")
                            + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                List<Object> paged = new ArrayList<>(parameters);
                paged.add(pageSize);
                paged.add((page - 1) * pageSize);
                bind(statement, paged);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Timestamp updatedAt = result.getTimestamp("updated_at");
                        rows.add(new CopyRecord(
                                result.getLong("id"), result.getLong("book_id"),
                                result.getString("barcode"), result.getString("title"),
                                result.getString("shelf_location"),
                                parseStatus(result.getString("status")),
                                result.getString("status_reason"),
                                updatedAt == null ? Instant.EPOCH : updatedAt.toInstant()));
                    }
                }
            }
            return new CopyPage(rows, page, pageSize, total);
        }
    }

    @Override
    public CreatedCopy createCopy(CreateCopy command) throws SQLException {
        Objects.requireNonNull(command, "command");
        String location = requireText(command.shelfLocation(), "馆藏位置不能为空");
        String sql = "INSERT INTO book_copies (book_id, barcode, shelf_location, status) VALUES (?, ?, ?, 'AVAILABLE')";
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String requestedBarcode = trim(command.barcode());
                String barcode;
                if (requestedBarcode.isBlank()) {
                    barcode = formatCode("B", nextCode(connection, "COPY_BARCODE"));
                } else {
                    barcode = LibraryCodePolicy.requireValidBarcode(requestedBarcode);
                    advanceCodePast(connection, "COPY_BARCODE",
                            Long.parseLong(barcode.substring(1)) + 1);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, command.bookId());
                    statement.setString(2, barcode);
                    statement.setString(3, location);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("数据库未返回馆藏ID");
                        }
                        CreatedCopy created = new CreatedCopy(keys.getLong(1), barcode);
                        connection.commit();
                        return created;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public MutationResult setCopyStatus(long copyId, LibraryCopyStatus status, String reason)
            throws SQLException {
        Objects.requireNonNull(status, "status");
        String normalizedReason = trimToNull(reason);
        if (requiresReason(status) && normalizedReason == null) {
            throw new IllegalArgumentException("异常馆藏状态必须填写原因");
        }
        if (status == LibraryCopyStatus.AVAILABLE) {
            normalizedReason = null;
        }
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LibraryCopyStatus current;
                String currentReason;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT status, status_reason FROM book_copies WHERE id = ? FOR UPDATE")) {
                    statement.setLong(1, copyId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return MutationResult.NOT_FOUND;
                        }
                        current = parseStatus(result.getString("status"));
                        currentReason = result.getString("status_reason");
                    }
                }
                if (current == LibraryCopyStatus.ON_LOAN || status == LibraryCopyStatus.ON_LOAN) {
                    connection.rollback();
                    return MutationResult.CONFLICT;
                }
                if (current == status && Objects.equals(currentReason, normalizedReason)) {
                    connection.rollback();
                    return MutationResult.UNCHANGED;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE book_copies SET status = ?, status_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    statement.setString(1, status.name());
                    if (normalizedReason == null) {
                        statement.setNull(2, Types.VARCHAR);
                    } else {
                        statement.setString(2, normalizedReason);
                    }
                    statement.setLong(3, copyId);
                    statement.executeUpdate();
                }
                connection.commit();
                return MutationResult.CHANGED;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private int countBooks(Connection connection, String keyword, String category,
                           boolean includeDisabled) throws SQLException {
        String sql = "SELECT COUNT(*) FROM books b" + catalogWhere(includeDisabled);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCatalogFilters(statement, keyword, category);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private List<CatalogItem> queryBooks(Connection connection, String keyword, String category,
                                         boolean includeDisabled, boolean newestFirst,
                                         int page, int pageSize) throws SQLException {
        String sql = catalogSelect() + catalogWhere(includeDisabled) + catalogGroupBy()
                + (newestFirst ? " ORDER BY b.id DESC" : " ORDER BY b.title, b.id")
                + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindCatalogFilters(statement, keyword, category);
            statement.setInt(index++, pageSize);
            statement.setInt(index, (page - 1) * pageSize);
            List<CatalogItem> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(readCatalogItem(result));
                }
            }
            return rows;
        }
    }

    private String catalogSelect() {
        return """
                SELECT b.id, b.catalog_code, b.isbn, b.title, b.authors, b.publisher, b.publish_year,
                       b.category, b.description, b.enabled,
                       COUNT(c.id) AS total_copies,
                       COALESCE(SUM(CASE WHEN c.status = 'AVAILABLE' THEN 1 ELSE 0 END), 0) AS available_copies
                  FROM books b
                  LEFT JOIN book_copies c ON c.book_id = b.id
                """;
    }

    private String catalogWhere(boolean includeDisabled) {
        return """
                 WHERE (? = '' OR b.title LIKE ? ESCAPE '!' OR b.authors LIKE ? ESCAPE '!'
                        OR b.catalog_code = ? OR b.isbn = ?)
                   AND (? = '' OR b.category = ?)
                """ + (includeDisabled ? "" : " AND b.enabled = TRUE");
    }

    private String catalogGroupBy() {
        return " GROUP BY b.id, b.catalog_code, b.isbn, b.title, b.authors, b.publisher, b.publish_year,"
                + " b.category, b.description, b.enabled";
    }

    private int bindCatalogFilters(PreparedStatement statement, String keyword, String category)
            throws SQLException {
        String pattern = "%" + escapeLike(keyword) + "%";
        String isbnKeyword = keyword.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        statement.setString(1, keyword);
        statement.setString(2, pattern);
        statement.setString(3, pattern);
        statement.setString(4, keyword.toUpperCase(Locale.ROOT));
        statement.setString(5, isbnKeyword);
        statement.setString(6, category);
        statement.setString(7, category);
        return 8;
    }

    private CatalogItem readCatalogItem(ResultSet result) throws SQLException {
        int publishYearValue = result.getInt("publish_year");
        Integer publishYear = result.wasNull() ? null : publishYearValue;
        return new CatalogItem(
                result.getLong("id"), result.getString("catalog_code"), result.getString("isbn"), result.getString("title"),
                result.getString("authors"), result.getString("publisher"), publishYear,
                result.getString("category"), result.getString("description"),
                result.getBoolean("enabled"), result.getInt("total_copies"),
                result.getInt("available_copies"));
    }

    private BookCommand normalize(BookCommand command) {
        Objects.requireNonNull(command, "command");
        Integer year = command.publishYear();
        if (year != null && (year < 1000 || year > 9999)) {
            throw new IllegalArgumentException("出版年份格式不正确");
        }
        return new BookCommand(
                trim(command.isbn()).isBlank() ? null : LibraryCodePolicy.normalizeIsbn(command.isbn()),
                requireText(command.title(), "书名不能为空"),
                requireText(command.authors(), "作者不能为空"),
                requireText(command.publisher(), "出版社不能为空"),
                year,
                requireText(command.category(), "分类不能为空"),
                trimToNull(command.description()));
    }

    private void bindBook(PreparedStatement statement, BookCommand command, int start) throws SQLException {
        if (command.isbn() == null) statement.setNull(start, Types.VARCHAR);
        else statement.setString(start, command.isbn());
        statement.setString(start + 1, command.title());
        statement.setString(start + 2, command.authors());
        statement.setString(start + 3, command.publisher());
        if (command.publishYear() == null) {
            statement.setNull(start + 4, Types.SMALLINT);
        } else {
            statement.setInt(start + 4, command.publishYear());
        }
        statement.setString(start + 5, command.category());
        if (command.description() == null) {
            statement.setNull(start + 6, Types.VARCHAR);
        } else {
            statement.setString(start + 6, command.description());
        }
    }

    private long nextCode(Connection connection, String codeType) throws SQLException {
        long next;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT next_value FROM library_code_sequences WHERE code_type = ? FOR UPDATE")) {
            statement.setString(1, codeType);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("缺少编号序列：" + codeType);
                next = result.getLong(1);
            }
        }
        if (next < 1 || next > 999_999_999L) throw new SQLException("编号序列已超出范围：" + codeType);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE library_code_sequences SET next_value = ? WHERE code_type = ?")) {
            statement.setLong(1, next + 1);
            statement.setString(2, codeType);
            statement.executeUpdate();
        }
        return next;
    }

    private String formatCode(String prefix, long value) {
        return prefix + String.format(Locale.ROOT, "%09d", value);
    }

    private void advanceCodePast(Connection connection, String codeType, long floor)
            throws SQLException {
        long current;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT next_value FROM library_code_sequences WHERE code_type = ? FOR UPDATE")) {
            statement.setString(1, codeType);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("缺少编号序列：" + codeType);
                current = result.getLong(1);
            }
        }
        if (floor <= current) return;
        if (floor > 1_000_000_000L) throw new SQLException("编号序列已超出范围：" + codeType);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE library_code_sequences SET next_value = ? WHERE code_type = ?")) {
            statement.setLong(1, floor);
            statement.setString(2, codeType);
            statement.executeUpdate();
        }
    }

    private List<Object> appendCopyFilters(StringBuilder sql, Long bookId, String keyword,
                                           LibraryCopyStatus status) {
        List<Object> parameters = new ArrayList<>();
        if (bookId != null) {
            sql.append(" AND c.book_id = ?");
            parameters.add(bookId);
        }
        if (!keyword.isBlank()) {
            sql.append(" AND (c.barcode LIKE ? ESCAPE '!' OR b.title LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(keyword) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (status != null) {
            sql.append(" AND c.status = ?");
            parameters.add(status.name());
        }
        return parameters;
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private boolean exists(Connection connection, String table, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private LibraryCopyStatus parseStatus(String value) throws SQLException {
        try {
            return LibraryCopyStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("数据库包含未知馆藏状态: " + value, exception);
        }
    }

    private boolean requiresReason(LibraryCopyStatus status) {
        return status == LibraryCopyStatus.LOST
                || status == LibraryCopyStatus.DAMAGED
                || status == LibraryCopyStatus.WITHDRAWN;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String normalized = trim(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
