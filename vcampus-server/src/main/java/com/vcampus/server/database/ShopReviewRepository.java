package com.vcampus.server.database;

import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.server.model.ShopReviewImageRecord;
import com.vcampus.server.model.ShopReviewRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ShopReviewRepository implements ShopReviewStore {
    @FunctionalInterface
    interface ConnectionProvider {
        Connection openConnection() throws SQLException;
    }

    private static final String REVIEW_COLUMNS =
            "r.id,r.order_item_id,r.product_id,r.author_user_id,u.display_name,"
                    + "r.rating,r.content,r.repeat_purchase,r.status,r.created_at,r.updated_at";
    private final ConnectionProvider connections;

    public ShopReviewRepository(ConnectionFactory connections) {
        this(Objects.requireNonNull(connections, "connections")::openConnection);
    }

    ShopReviewRepository(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public ReviewMutationResult create(long authorUserId, CreateReview command) throws SQLException {
        positive(authorUserId, "用户ID无效");
        Objects.requireNonNull(command, "command");
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ShopReviewRecord duplicate = byOperation(connection, command.operationId(), true);
                if (duplicate != null) {
                    verifyDuplicate(duplicate, authorUserId, command.orderItemId());
                    connection.commit();
                    return new ReviewMutationResult(duplicate, true, imageKeys(connection, duplicate.id()), Set.of());
                }
                OrderItem item = lockOrderItem(connection, command.orderItemId());
                if (item == null) throw new ShopRuleException("订单商品不存在");
                if (item.buyerUserId != authorUserId) throw new ShopRuleException("不能评价他人订单商品");
                if (!"COMPLETED".equals(item.status)) throw new ShopRuleException("确认收货后才能评价");
                if (reviewIdByOrderItem(connection, command.orderItemId()) != null) {
                    throw new ShopRuleException("该订单商品已经评价");
                }
                boolean repeat = isRepeatPurchase(connection, item);
                long reviewId = insertReview(connection, authorUserId, command, item.productId, repeat);
                insertImages(connection, reviewId, command.images());
                ShopReviewRecord review = byId(connection, reviewId);
                Set<String> kept = imageKeys(connection, reviewId);
                connection.commit();
                return new ReviewMutationResult(review, false, kept, Set.of());
            } catch (SQLException failure) {
                rollback(connection, failure);
                ShopReviewRecord duplicate = byOperation(connection, command.operationId(), false);
                if (duplicate != null) {
                    verifyDuplicate(duplicate, authorUserId, command.orderItemId());
                    return new ReviewMutationResult(duplicate, true,
                            imageKeys(connection, duplicate.id()), Set.of());
                }
                throw failure;
            } catch (RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public ReviewMutationResult update(long authorUserId, UpdateReview command) throws SQLException {
        positive(authorUserId, "用户ID无效");
        Objects.requireNonNull(command, "command");
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LockedReview locked = lockReview(connection, command.reviewId());
                if (locked == null) throw new ShopRuleException("评价不存在");
                if (locked.authorUserId != authorUserId) throw new ShopRuleException("只能修改自己的评价");
                if (locked.status != ShopReviewStatus.NORMAL) throw new ShopRuleException("当前评价不可修改");

                Map<Long, ShopReviewImageRecord> existing = imagesById(connection, command.reviewId());
                List<PendingImage> finalImages = new ArrayList<>();
                Set<Long> retainedIds = new LinkedHashSet<>();
                for (ImagePlanItem item : command.imagePlan().items()) {
                    if (item.existingImageId() != null) {
                        ShopReviewImageRecord image = existing.get(item.existingImageId());
                        if (image == null || !retainedIds.add(image.id())) {
                            throw new ShopRuleException("评价图片计划无效");
                        }
                        finalImages.add(toPending(image));
                    } else {
                        finalImages.add(item.upload());
                    }
                }
                Set<String> deleted = new LinkedHashSet<>();
                for (ShopReviewImageRecord image : existing.values()) {
                    if (!retainedIds.contains(image.id())) {
                        deleted.add(image.storageKey());
                        deleted.add(image.thumbnailStorageKey());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE shop_product_reviews SET rating=?,content=? WHERE id=?")) {
                    statement.setInt(1, command.rating());
                    statement.setString(2, command.content());
                    statement.setLong(3, command.reviewId());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM shop_review_images WHERE review_id=?")) {
                    statement.setLong(1, command.reviewId());
                    statement.executeUpdate();
                }
                insertImages(connection, command.reviewId(), finalImages);
                ShopReviewRecord review = byId(connection, command.reviewId());
                Set<String> kept = imageKeys(connection, command.reviewId());
                connection.commit();
                return new ReviewMutationResult(review, false, kept, deleted);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public boolean delete(long authorUserId, long reviewId) throws SQLException {
        positive(authorUserId, "用户ID无效");
        positive(reviewId, "评价ID无效");
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LockedReview locked = lockReview(connection, reviewId);
                if (locked == null) throw new ShopRuleException("评价不存在");
                if (locked.authorUserId != authorUserId) throw new ShopRuleException("只能删除自己的评价");
                if (locked.status != ShopReviewStatus.NORMAL) {
                    connection.commit();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE shop_product_reviews SET status='DELETED',deleted_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    statement.setLong(1, reviewId);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public Map<Long, Long> reviewIdsByOrderItems(Set<Long> orderItemIds) throws SQLException {
        Objects.requireNonNull(orderItemIds, "orderItemIds");
        if (orderItemIds.isEmpty()) return Map.of();
        for (Long id : orderItemIds) if (id == null || id < 1) throw new IllegalArgumentException("订单商品ID无效");
        String placeholders = String.join(",", java.util.Collections.nCopies(orderItemIds.size(), "?"));
        Map<Long, Long> result = new LinkedHashMap<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT order_item_id,id FROM shop_product_reviews WHERE order_item_id IN (" + placeholders + ")")) {
            int index = 1;
            for (Long id : orderItemIds) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getLong(1), rows.getLong(2));
            }
        }
        return Map.copyOf(result);
    }

    private ShopReviewRecord byOperation(Connection connection, String operationId, boolean lock)
            throws SQLException {
        String sql = "SELECT " + REVIEW_COLUMNS + " FROM shop_product_reviews r"
                + " JOIN users u ON u.id=r.author_user_id WHERE r.operation_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapReview(result) : null;
            }
        }
    }

    private ShopReviewRecord byId(Connection connection, long reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + REVIEW_COLUMNS + " FROM shop_product_reviews r"
                        + " JOIN users u ON u.id=r.author_user_id WHERE r.id=?")) {
            statement.setLong(1, reviewId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ShopRuleException("评价不存在");
                return mapReview(result);
            }
        }
    }

    private OrderItem lockOrderItem(Connection connection, long orderItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT oi.id,oi.product_id,o.id,o.buyer_user_id,o.status,o.completed_at"
                        + " FROM shop_order_items oi JOIN shop_orders o ON o.id=oi.order_id"
                        + " WHERE oi.id=? FOR UPDATE")) {
            statement.setLong(1, orderItemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new OrderItem(result.getLong(1), result.getLong(2), result.getLong(3),
                        result.getLong(4), result.getString(5), result.getTimestamp(6));
            }
        }
    }

    private Long reviewIdByOrderItem(Connection connection, long orderItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM shop_product_reviews WHERE order_item_id=? FOR UPDATE")) {
            statement.setLong(1, orderItemId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private boolean isRepeatPurchase(Connection connection, OrderItem current) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM shop_order_items oi JOIN shop_orders o ON o.id=oi.order_id"
                        + " WHERE o.buyer_user_id=? AND oi.product_id=? AND o.status='COMPLETED'"
                        + " AND (o.completed_at<? OR (o.completed_at=? AND o.id<?))")) {
            statement.setLong(1, current.buyerUserId);
            statement.setLong(2, current.productId);
            statement.setTimestamp(3, current.completedAt);
            statement.setTimestamp(4, current.completedAt);
            statement.setLong(5, current.orderId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private long insertReview(Connection connection, long authorUserId, CreateReview command,
                              long productId, boolean repeat) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_product_reviews(operation_id,order_item_id,product_id,"
                        + "author_user_id,rating,content,repeat_purchase,status)"
                        + " VALUES(?,?,?,?,?,?,?,'NORMAL')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, command.operationId());
            statement.setLong(2, command.orderItemId());
            statement.setLong(3, productId);
            statement.setLong(4, authorUserId);
            statement.setInt(5, command.rating());
            statement.setString(6, command.content());
            statement.setBoolean(7, repeat);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("创建评价未返回ID");
                return keys.getLong(1);
            }
        }
    }

    private void insertImages(Connection connection, long reviewId, List<PendingImage> images)
            throws SQLException {
        if (images.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO shop_review_images(review_id,position,storage_key,"
                        + "thumbnail_storage_key,mime_type,byte_size,sha256,width,height)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (int position = 0; position < images.size(); position++) {
                PendingImage image = images.get(position);
                statement.setLong(1, reviewId);
                statement.setInt(2, position);
                statement.setString(3, image.storageKey());
                statement.setString(4, image.thumbnailStorageKey());
                statement.setString(5, image.mimeType());
                statement.setLong(6, image.byteSize());
                statement.setString(7, image.sha256());
                statement.setInt(8, image.width());
                statement.setInt(9, image.height());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Map<Long, ShopReviewImageRecord> imagesById(Connection connection, long reviewId)
            throws SQLException {
        Map<Long, ShopReviewImageRecord> images = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,review_id,position,storage_key,thumbnail_storage_key,mime_type,"
                        + "byte_size,sha256,width,height,created_at FROM shop_review_images"
                        + " WHERE review_id=? ORDER BY position,id")) {
            statement.setLong(1, reviewId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ShopReviewImageRecord image = mapImage(result);
                    images.put(image.id(), image);
                }
            }
        }
        return images;
    }

    private Set<String> imageKeys(Connection connection, long reviewId) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        for (ShopReviewImageRecord image : imagesById(connection, reviewId).values()) {
            keys.add(image.storageKey());
            keys.add(image.thumbnailStorageKey());
        }
        return Set.copyOf(keys);
    }

    private LockedReview lockReview(Connection connection, long reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT author_user_id,status FROM shop_product_reviews WHERE id=? FOR UPDATE")) {
            statement.setLong(1, reviewId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new LockedReview(result.getLong(1),
                        ShopReviewStatus.valueOf(result.getString(2))) : null;
            }
        }
    }

    private ShopReviewRecord mapReview(ResultSet result) throws SQLException {
        return new ShopReviewRecord(result.getLong(1), result.getLong(2), result.getLong(3),
                result.getLong(4), result.getString(5), result.getInt(6), result.getString(7),
                result.getBoolean(8), ShopReviewStatus.valueOf(result.getString(9)), 0, false,
                instant(result.getTimestamp(10)), instant(result.getTimestamp(11)));
    }

    private ShopReviewImageRecord mapImage(ResultSet result) throws SQLException {
        return new ShopReviewImageRecord(result.getLong(1), result.getLong(2), result.getInt(3),
                result.getString(4), result.getString(5), result.getString(6), result.getLong(7),
                result.getString(8), result.getInt(9), result.getInt(10),
                instant(result.getTimestamp(11)));
    }

    private PendingImage toPending(ShopReviewImageRecord image) {
        return new PendingImage(image.storageKey(), image.thumbnailStorageKey(), image.mimeType(),
                image.byteSize(), image.sha256(), image.width(), image.height());
    }

    private void verifyDuplicate(ShopReviewRecord review, long authorUserId, long orderItemId) {
        if (review.authorUserId() != authorUserId || review.orderItemId() != orderItemId) {
            throw new ShopRuleException("操作编号已被其他评价使用");
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private static void positive(long value, String message) {
        if (value < 1) throw new IllegalArgumentException(message);
    }

    private record OrderItem(long id, long productId, long orderId, long buyerUserId,
                             String status, Timestamp completedAt) {
    }

    private record LockedReview(long authorUserId, ShopReviewStatus status) {
    }
}
