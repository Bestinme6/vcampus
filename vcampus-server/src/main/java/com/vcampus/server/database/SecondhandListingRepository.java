package com.vcampus.server.database;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SecondhandListingRepository implements SecondhandListingStore {
    private static final String COLUMNS = "i.id,i.seller_user_id,u.display_name,"
            + "i.title,i.description,i.category,i.condition_text,i.price,"
            + "i.item_status,i.moderation_status,i.created_at,i.updated_at";

    private final ConnectionFactory connections;

    public SecondhandListingRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public long create(long sellerId, ListingInput input) throws SQLException {
        positive(sellerId, "卖家编号无效");
        Objects.requireNonNull(input, "input");
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO secondhand_items"
                             + "(seller_user_id,title,description,category,condition_text,price)"
                             + " VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, sellerId);
            bindInput(statement, input, 2);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated secondhand item id");
                return keys.getLong(1);
            }
        }
    }

    @Override
    public Listing update(long actorId, long itemId, ListingInput input) throws SQLException {
        positive(actorId, "用户编号无效");
        positive(itemId, "物品编号无效");
        Objects.requireNonNull(input, "input");
        return transaction(connection -> {
            Listing current = lock(connection, itemId);
            requireOwnedAvailableVisible(current, actorId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_items SET title=?,description=?,category=?,"
                            + "condition_text=?,price=? WHERE id=?")) {
                bindInput(statement, input, 1);
                statement.setLong(6, itemId);
                if (statement.executeUpdate() != 1) throw new SQLException("Secondhand item update failed");
            }
            return find(connection, itemId);
        });
    }

    @Override
    public Listing withdraw(long actorId, long itemId) throws SQLException {
        positive(actorId, "用户编号无效");
        positive(itemId, "物品编号无效");
        return transaction(connection -> {
            Listing current = lock(connection, itemId);
            if (current.sellerId() != actorId || current.status() != SecondhandItemStatus.AVAILABLE) {
                throw new SecondhandRuleException("无权下架该二手物品");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_items SET item_status='WITHDRAWN' WHERE id=?")) {
                statement.setLong(1, itemId);
                if (statement.executeUpdate() != 1) throw new SQLException("Secondhand item withdraw failed");
            }
            return find(connection, itemId);
        });
    }

    @Override
    public Listing moderate(long adminId, long itemId, boolean visible, String reason)
            throws SQLException {
        positive(adminId, "管理员编号无效");
        positive(itemId, "物品编号无效");
        String normalizedReason = text(reason, 2, 500, "审核原因无效");
        SecondhandModerationStatus target = visible
                ? SecondhandModerationStatus.VISIBLE : SecondhandModerationStatus.HIDDEN;
        return transaction(connection -> {
            Listing current = lock(connection, itemId);
            if (current.moderation() == target) return current;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_items SET moderation_status=? WHERE id=?")) {
                statement.setString(1, target.name());
                statement.setLong(2, itemId);
                if (statement.executeUpdate() != 1) throw new SQLException("Secondhand moderation failed");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO secondhand_listing_moderation_logs"
                            + "(item_id,operator_user_id,action,reason) VALUES(?,?,?,?)")) {
                statement.setLong(1, itemId);
                statement.setLong(2, adminId);
                statement.setString(3, visible ? "RESTORE" : "HIDE");
                statement.setString(4, normalizedReason);
                statement.executeUpdate();
            }
            return find(connection, itemId);
        });
    }

    @Override
    public ListingPage search(String keyword, int page, int pageSize) throws SQLException {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("分页参数无效");
        }
        String normalized = Objects.requireNonNullElse(keyword, "").trim();
        String pattern = "%" + normalized + "%";
        String where = " FROM secondhand_items i JOIN users u ON u.id=i.seller_user_id"
                + " WHERE i.item_status='AVAILABLE' AND i.moderation_status='VISIBLE'"
                + " AND (?='' OR LOWER(i.title) LIKE LOWER(?)"
                + " OR LOWER(i.description) LIKE LOWER(?))";
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + where)) {
                bindSearch(statement, normalized, pattern);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<Listing> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + where
                            + " ORDER BY i.created_at DESC,i.id DESC LIMIT ? OFFSET ?")) {
                bindSearch(statement, normalized, pattern);
                statement.setInt(4, pageSize);
                statement.setInt(5, (page - 1) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(row(result));
                }
            }
            return new ListingPage(rows, page, pageSize, total);
        }
    }

    @Override
    public Listing get(long itemId, long viewerId, boolean admin) throws SQLException {
        positive(itemId, "物品编号无效");
        positive(viewerId, "用户编号无效");
        try (Connection connection = connections.openConnection()) {
            Listing listing = find(connection, itemId);
            boolean publicListing = listing.status() == SecondhandItemStatus.AVAILABLE
                    && listing.moderation() == SecondhandModerationStatus.VISIBLE;
            if (!admin && listing.sellerId() != viewerId && !publicListing) {
                throw new SecondhandRuleException("二手物品不可访问");
            }
            return listing;
        }
    }

    private Listing lock(Connection connection, long itemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM secondhand_items i JOIN users u"
                        + " ON u.id=i.seller_user_id WHERE i.id=? FOR UPDATE")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecondhandRuleException("二手物品不存在");
                return row(result);
            }
        }
    }

    private Listing find(Connection connection, long itemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM secondhand_items i JOIN users u"
                        + " ON u.id=i.seller_user_id WHERE i.id=?")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecondhandRuleException("二手物品不存在");
                return row(result);
            }
        }
    }

    private static Listing row(ResultSet result) throws SQLException {
        return new Listing(result.getLong(1), result.getLong(2), result.getString(3),
                result.getString(4), result.getString(5), result.getString(6),
                result.getString(7), result.getBigDecimal(8),
                SecondhandItemStatus.valueOf(result.getString(9)),
                SecondhandModerationStatus.valueOf(result.getString(10)),
                result.getTimestamp(11).toInstant(), result.getTimestamp(12).toInstant());
    }

    private static void bindInput(PreparedStatement statement, ListingInput input, int offset)
            throws SQLException {
        statement.setString(offset, input.title());
        statement.setString(offset + 1, input.description());
        statement.setString(offset + 2, input.category());
        statement.setString(offset + 3, input.condition());
        statement.setBigDecimal(offset + 4, input.price());
    }

    private static void bindSearch(PreparedStatement statement, String keyword, String pattern)
            throws SQLException {
        statement.setString(1, keyword);
        statement.setString(2, pattern);
        statement.setString(3, pattern);
    }

    private static void requireOwnedAvailableVisible(Listing listing, long actorId) {
        if (listing.sellerId() != actorId
                || listing.status() != SecondhandItemStatus.AVAILABLE
                || listing.moderation() != SecondhandModerationStatus.VISIBLE) {
            throw new SecondhandRuleException("无权修改该二手物品");
        }
    }

    private static void positive(long value, String message) {
        if (value < 1) throw new IllegalArgumentException(message);
    }

    private static String text(String value, int minimum, int maximum, String message) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() < minimum || normalized.length() > maximum
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
