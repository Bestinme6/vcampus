package com.vcampus.server.database;

import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopReviewStatus;
import com.vcampus.server.database.SecondhandReviewStore.LikeResult;
import com.vcampus.server.database.SecondhandReviewStore.MutationResult;
import com.vcampus.server.database.SecondhandReviewStore.PendingImage;
import com.vcampus.server.database.SecondhandReviewStore.Review;
import com.vcampus.server.database.SecondhandReviewStore.ReviewImage;
import com.vcampus.server.database.SecondhandReviewStore.ReviewPage;
import com.vcampus.server.database.SecondhandReviewStore.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SecondhandReviewRepository implements SecondhandReviewStore {
    private static final String COLUMNS = "r.id,r.order_id,r.item_id,r.seller_user_id,"
            + "r.author_user_id,u.display_name,r.rating,r.content,r.status,r.created_at,"
            + "r.updated_at";
    private final ConnectionFactory connections;

    public SecondhandReviewRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Optional<MutationResult> findCreated(long buyerId, String operationId, long orderId)
            throws SQLException {
        positive(buyerId, "用户编号无效"); positive(orderId, "订单编号无效");
        String operation = operation(operationId);
        try (Connection connection = connections.openConnection()) {
            Review duplicate = byOperation(connection, operation, buyerId);
            if (duplicate == null) return Optional.empty();
            if (duplicate.authorId() != buyerId || duplicate.orderId() != orderId) {
                throw new SecondhandRuleException("该业务编号已被使用");
            }
            return Optional.of(new MutationResult(duplicate, true,
                    imageKeys(connection, duplicate.id())));
        }
    }

    @Override
    public MutationResult create(long buyerId, String operationId, long orderId, int rating,
                                 String content, List<PendingImage> images) throws SQLException {
        positive(buyerId, "用户编号无效");
        positive(orderId, "订单编号无效");
        String operation = operation(operationId);
        SecondhandReviewStore.rating(rating);
        String normalized = SecondhandReviewStore.reviewContent(content);
        List<PendingImage> pending = List.copyOf(Objects.requireNonNull(images, "images"));
        if (pending.size() > 3) throw new IllegalArgumentException("评价最多上传3张图片");
        return transaction(connection -> {
            Review duplicate = byOperation(connection, operation, buyerId);
            if (duplicate != null) {
                if (duplicate.authorId() != buyerId || duplicate.orderId() != orderId) {
                    throw new SecondhandRuleException("该业务编号已被使用");
                }
                return new MutationResult(duplicate, true, imageKeys(connection, duplicate.id()));
            }
            OrderLock order = lockOrder(connection, orderId);
            if (order.buyerId() != buyerId || order.status() != ShopOrderStatus.COMPLETED) {
                throw new SecondhandRuleException("仅已完成订单的买家可以评价");
            }
            if (reviewByOrder(connection, orderId) != null) {
                throw new SecondhandRuleException("该订单已经评价");
            }
            long reviewId = insertReview(connection, operation, order, rating, normalized);
            insertImages(connection, reviewId, pending);
            return new MutationResult(byId(connection, reviewId, buyerId), false,
                    imageKeys(connection, reviewId));
        });
    }

    @Override
    public MutationResult update(long actorId, long reviewId, int rating, String content)
            throws SQLException {
        positive(actorId, "用户编号无效"); positive(reviewId, "评价编号无效");
        SecondhandReviewStore.rating(rating);
        String normalized = SecondhandReviewStore.reviewContent(content);
        return transaction(connection -> {
            Review current = byIdForUpdate(connection, reviewId, actorId);
            if (current.authorId() != actorId) throw new SecondhandRuleException("只能修改自己的评价");
            if (current.status() != ShopReviewStatus.NORMAL) throw new SecondhandRuleException("当前评价不可修改");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_reviews SET rating=?,content=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                statement.setInt(1, rating); statement.setString(2, normalized);
                statement.setLong(3, reviewId); statement.executeUpdate();
            }
            return new MutationResult(byId(connection, reviewId, actorId), false,
                    imageKeys(connection, reviewId));
        });
    }

    @Override
    public boolean delete(long actorId, long reviewId) throws SQLException {
        positive(actorId, "用户编号无效"); positive(reviewId, "评价编号无效");
        return transaction(connection -> {
            Review current = byIdForUpdate(connection, reviewId, actorId);
            if (current.authorId() != actorId) throw new SecondhandRuleException("只能删除自己的评价");
            if (current.status() == ShopReviewStatus.DELETED) return false;
            if (current.status() != ShopReviewStatus.NORMAL) throw new SecondhandRuleException("当前评价不可删除");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_reviews SET status='DELETED',deleted_at=CURRENT_TIMESTAMP,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                statement.setLong(1, reviewId); statement.executeUpdate(); return true;
            }
        });
    }

    @Override
    public LikeResult setLike(long actorId, long reviewId, boolean enabled) throws SQLException {
        positive(actorId, "用户编号无效"); positive(reviewId, "评价编号无效");
        return transaction(connection -> {
            Review review = byIdForUpdate(connection, reviewId, actorId);
            if (review.status() != ShopReviewStatus.NORMAL) throw new SecondhandRuleException("当前评价不可点赞");
            if (review.authorId() == actorId) throw new SecondhandRuleException("不能点赞自己的评价");
            int changed;
            if (enabled) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT IGNORE INTO secondhand_review_likes(review_id,user_id) VALUES(?,?)")) {
                    statement.setLong(1, reviewId); statement.setLong(2, actorId);
                    changed = statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM secondhand_review_likes WHERE review_id=? AND user_id=?")) {
                    statement.setLong(1, reviewId); statement.setLong(2, actorId);
                    changed = statement.executeUpdate();
                }
            }
            return new LikeResult(reviewId, enabled, changed == 1, likeCount(connection, reviewId));
        });
    }

    @Override
    public ReviewPage listSeller(long sellerId, long viewerId, int page, int pageSize)
            throws SQLException {
        positive(sellerId, "卖家编号无效"); positive(viewerId, "用户编号无效"); paging(page, pageSize);
        return page(" WHERE r.seller_user_id=? AND r.status='NORMAL'", List.of(sellerId),
                viewerId, page, pageSize);
    }

    @Override
    public ReviewPage searchAdmin(String keyword, ShopReviewStatus status, int page, int pageSize)
            throws SQLException {
        paging(page, pageSize);
        String term = Objects.requireNonNullElse(keyword, "").trim();
        if (term.length() > 100) throw new IllegalArgumentException("查询关键字过长");
        // Avoid SQL string-literal backslash semantics differing between MySQL and H2.
        String like = "%" + term.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        return page(" WHERE (?='' OR r.content LIKE ? ESCAPE '!' OR u.display_name LIKE ? ESCAPE '!')"
                        + " AND (? IS NULL OR r.status=?)",
                Arrays.asList(term, like, like, status == null ? null : status.name(),
                        status == null ? null : status.name()), 0L, page, pageSize);
    }

    @Override
    public Summary summary(long sellerId) throws SQLException {
        positive(sellerId, "卖家编号无效");
        int[] stars = new int[5]; int total = 0; int score = 0;
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT rating,COUNT(*) FROM secondhand_reviews "
                             + "WHERE seller_user_id=? AND status='NORMAL' GROUP BY rating")) {
            statement.setLong(1, sellerId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int rating = result.getInt(1); int count = result.getInt(2);
                    stars[rating - 1] = count; total += count; score += rating * count;
                }
            }
        }
        BigDecimal average = total == 0 ? null : BigDecimal.valueOf(score)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        return new Summary(average, total, Arrays.stream(stars).boxed().toList());
    }

    @Override
    public boolean moderate(long adminId, long reviewId, boolean hidden, String reason)
            throws SQLException {
        positive(adminId, "管理员编号无效"); positive(reviewId, "评价编号无效");
        String normalized = Objects.requireNonNullElse(reason, "").trim();
        if (normalized.length() < 2 || normalized.length() > 255) {
            throw new IllegalArgumentException("审核原因需为2到255字");
        }
        return transaction(connection -> {
            Review current = byIdForUpdate(connection, reviewId, adminId);
            ShopReviewStatus target = hidden ? ShopReviewStatus.HIDDEN : ShopReviewStatus.NORMAL;
            if (current.status() == ShopReviewStatus.DELETED) throw new SecondhandRuleException("已删除评价不能审核");
            if (current.status() == target) return false;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE secondhand_reviews SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                statement.setString(1, target.name()); statement.setLong(2, reviewId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO secondhand_review_moderation_logs"
                            + "(review_id,operator_user_id,action,reason) VALUES(?,?,?,?)")) {
                statement.setLong(1, reviewId); statement.setLong(2, adminId);
                statement.setString(3, hidden ? "HIDE" : "RESTORE");
                statement.setString(4, normalized); statement.executeUpdate();
            }
            return true;
        });
    }

    private ReviewPage page(String where, List<?> values, long viewerId, int page, int size)
            throws SQLException {
        try (Connection connection = connections.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM secondhand_reviews r JOIN users u ON u.id=r.author_user_id" + where)) {
                bind(statement, values); try (ResultSet result = statement.executeQuery()) {
                    result.next(); total = result.getInt(1);
                }
            }
            List<Review> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM secondhand_reviews r "
                            + "JOIN users u ON u.id=r.author_user_id" + where
                            + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?")) {
                int index = bind(statement, values); statement.setInt(index++, size);
                statement.setInt(index, (page - 1) * size);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(map(connection, result, viewerId));
                }
            }
            return new ReviewPage(rows, images(connection, rows), page, size, total);
        }
    }

    private static OrderLock lockOrder(Connection connection, long orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT item_id,buyer_user_id,seller_user_id,status FROM secondhand_orders "
                        + "WHERE id=? FOR UPDATE")) {
            statement.setLong(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecondhandRuleException("二手订单不存在");
                return new OrderLock(orderId, result.getLong(1), result.getLong(2),
                        result.getLong(3), ShopOrderStatus.valueOf(result.getString(4)));
            }
        }
    }

    private static long insertReview(Connection connection, String operation, OrderLock order,
                                     int rating, String content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO secondhand_reviews(operation_id,order_id,item_id,seller_user_id,"
                        + "author_user_id,rating,content) VALUES(?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, operation); statement.setLong(2, order.id());
            statement.setLong(3, order.itemId()); statement.setLong(4, order.sellerId());
            statement.setLong(5, order.buyerId()); statement.setInt(6, rating);
            statement.setString(7, content); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing secondhand review id");
                return keys.getLong(1);
            }
        }
    }

    private static void insertImages(Connection connection, long reviewId, List<PendingImage> images)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO secondhand_review_images(review_id,position,storage_key,"
                        + "thumbnail_storage_key,mime_type,byte_size,sha256,width,height)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (int position = 0; position < images.size(); position++) {
                PendingImage image = images.get(position);
                statement.setLong(1, reviewId); statement.setInt(2, position);
                statement.setString(3, image.storageKey());
                statement.setString(4, image.thumbnailStorageKey());
                statement.setString(5, image.mimeType()); statement.setLong(6, image.byteSize());
                statement.setString(7, image.sha256()); statement.setInt(8, image.width());
                statement.setInt(9, image.height()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Review byOperation(Connection c, String operation, long viewerId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS
                + " FROM secondhand_reviews r JOIN users u ON u.id=r.author_user_id "
                + "WHERE r.operation_id=?")) {
            s.setString(1, operation);
            try (ResultSet r = s.executeQuery()) { return r.next() ? map(c, r, viewerId) : null; }
        }
    }

    private static Long reviewByOrder(Connection c, long orderId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id FROM secondhand_reviews WHERE order_id=?")) {
            s.setLong(1, orderId); try (ResultSet r=s.executeQuery()) { return r.next()?r.getLong(1):null; }
        }
    }

    private static Review byId(Connection c, long reviewId, long viewerId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS
                + " FROM secondhand_reviews r JOIN users u ON u.id=r.author_user_id WHERE r.id=?")) {
            s.setLong(1, reviewId); try (ResultSet r=s.executeQuery()) {
                if (!r.next()) throw new SecondhandRuleException("评价不存在");
                return map(c, r, viewerId);
            }
        }
    }

    private static Review byIdForUpdate(Connection c, long reviewId, long viewerId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS
                + " FROM secondhand_reviews r JOIN users u ON u.id=r.author_user_id "
                + "WHERE r.id=? FOR UPDATE")) {
            s.setLong(1, reviewId); try (ResultSet r=s.executeQuery()) {
                if (!r.next()) throw new SecondhandRuleException("评价不存在");
                return map(c, r, viewerId);
            }
        }
    }

    private static Review map(Connection c, ResultSet r, long viewerId) throws SQLException {
        long id = r.getLong("id");
        return new Review(id, r.getLong("order_id"), r.getLong("item_id"),
                r.getLong("seller_user_id"), r.getLong("author_user_id"),
                r.getString("display_name"), r.getInt("rating"), r.getString("content"),
                ShopReviewStatus.valueOf(r.getString("status")), likeCount(c, id),
                viewerId > 0 && liked(c, id, viewerId), instant(r, "created_at"),
                instant(r, "updated_at"));
    }

    private static Map<Long, List<ReviewImage>> images(Connection c, List<Review> reviews)
            throws SQLException {
        if (reviews.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(reviews.size(), "?"));
        Map<Long, List<ReviewImage>> result = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id,review_id,position,mime_type,byte_size,sha256,width,height "
                        + "FROM secondhand_review_images WHERE review_id IN (" + placeholders
                        + ") ORDER BY review_id,position")) {
            int index=1; for (Review review:reviews) s.setLong(index++,review.id());
            try(ResultSet r=s.executeQuery()) { while(r.next()) result.computeIfAbsent(r.getLong(2), ignored->new ArrayList<>())
                    .add(new ReviewImage(r.getLong(1),r.getLong(2),r.getInt(3),r.getString(4),
                            r.getLong(5),r.getString(6),r.getInt(7),r.getInt(8))); }
        }
        return result;
    }

    private static Set<String> imageKeys(Connection c, long reviewId) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (PreparedStatement s=c.prepareStatement("SELECT storage_key,thumbnail_storage_key "
                + "FROM secondhand_review_images WHERE review_id=?")) {
            s.setLong(1,reviewId); try(ResultSet r=s.executeQuery()){while(r.next()){keys.add(r.getString(1));keys.add(r.getString(2));}}
        }
        return Set.copyOf(keys);
    }

    private static int likeCount(Connection c,long reviewId)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM secondhand_review_likes WHERE review_id=?")){s.setLong(1,reviewId);try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1);}}}
    private static boolean liked(Connection c,long reviewId,long userId)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM secondhand_review_likes WHERE review_id=? AND user_id=?")){s.setLong(1,reviewId);s.setLong(2,userId);try(ResultSet r=s.executeQuery()){return r.next();}}}
    private <T>T transaction(Work<T> work)throws SQLException{try(Connection c=connections.openConnection()){boolean auto=c.getAutoCommit();c.setAutoCommit(false);try{T value=work.run(c);c.commit();return value;}catch(SQLException|RuntimeException error){try{c.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}throw error;}finally{c.setAutoCommit(auto);}}}
    private static int bind(PreparedStatement s,List<?> values)throws SQLException{int i=1;for(Object value:values)s.setObject(i++,value);return i;}
    private static Instant instant(ResultSet r,String column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
    private static String operation(String value){try{return UUID.fromString(Objects.requireNonNullElse(value,"").trim()).toString();}catch(IllegalArgumentException error){throw new IllegalArgumentException("业务编号无效");}}
    private static void positive(long value,String message){if(value<1)throw new IllegalArgumentException(message);}
    private static void paging(int page,int size){if(page<1||size<1||size>100)throw new IllegalArgumentException("分页参数无效");}
    private record OrderLock(long id,long itemId,long buyerId,long sellerId,ShopOrderStatus status){}
    @FunctionalInterface private interface Work<T>{T run(Connection connection)throws SQLException;}
}
