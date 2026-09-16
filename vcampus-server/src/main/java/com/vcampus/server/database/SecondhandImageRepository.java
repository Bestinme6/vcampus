package com.vcampus.server.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SecondhandImageRepository {
    private final ConnectionFactory connections;

    public SecondhandImageRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public List<ImageRow> list(long itemId) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            return list(connection, itemId);
        }
    }

    public ImageRow image(long imageId) throws SQLException {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,item_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                             + "sha256,sort_order,is_cover,created_at"
                             + " FROM secondhand_item_images WHERE id=?")) {
            statement.setLong(1, imageId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecondhandRuleException("二手物品图片不存在");
                return row(result);
            }
        }
    }

    public Set<String> storageKeys() throws SQLException {
        Set<String> keys = new HashSet<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT storage_key,thumbnail_storage_key FROM secondhand_item_images");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                keys.add(result.getString(1));
                keys.add(result.getString(2));
            }
        }
        return Set.copyOf(keys);
    }

    public CommitResult replace(long actorId, long itemId,
                                Map<String, FinalizedUpload> uploads, ImagePlan plan)
            throws SQLException {
        Objects.requireNonNull(uploads, "uploads");
        Objects.requireNonNull(plan, "plan");
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                lockWritableOwner(connection, actorId, itemId);
                List<ImageRow> before = list(connection, itemId);
                Set<Long> retained = new LinkedHashSet<>();
                for (ImagePlanItem item : plan.items()) {
                    if (item.existingImageId() != null) retained.add(item.existingImageId());
                }
                for (long id : retained) {
                    if (before.stream().noneMatch(row -> row.id() == id)) {
                        throw new SecondhandRuleException("图片不属于当前二手物品");
                    }
                }
                Set<String> deletedKeys = new LinkedHashSet<>();
                for (ImageRow row : before) {
                    if (!retained.contains(row.id())) {
                        deletedKeys.add(row.storageKey());
                        deletedKeys.add(row.thumbnailStorageKey());
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM secondhand_item_images WHERE item_id=?")) {
                    statement.setLong(1, itemId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO secondhand_item_images"
                                + "(id,item_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                                + "sha256,sort_order,is_cover) VALUES(?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    for (int index = 0; index < plan.items().size(); index++) {
                        ImagePlanItem item = plan.items().get(index);
                        FinalizedUpload source = item.uploadId() == null
                                ? existing(before, item.existingImageId())
                                : Objects.requireNonNull(uploads.get(item.uploadId()),
                                        "missing finalized upload");
                        if (item.existingImageId() == null) statement.setNull(1, java.sql.Types.BIGINT);
                        else statement.setLong(1, item.existingImageId());
                        statement.setLong(2, itemId);
                        statement.setString(3, source.storageKey());
                        statement.setString(4, source.thumbnailStorageKey());
                        statement.setString(5, source.mimeType());
                        statement.setLong(6, source.byteSize());
                        statement.setString(7, source.sha256());
                        statement.setInt(8, index);
                        statement.setBoolean(9, item.cover());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                List<ImageRow> rows = list(connection, itemId);
                connection.commit();
                return new CommitResult(rows, deletedKeys);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static FinalizedUpload existing(List<ImageRow> rows, Long id) {
        ImageRow row = rows.stream().filter(value -> value.id() == id).findFirst()
                .orElseThrow(() -> new SecondhandRuleException("图片不属于当前二手物品"));
        return new FinalizedUpload(row.storageKey(), row.thumbnailStorageKey(), row.mimeType(),
                row.byteSize(), row.sha256());
    }

    private static void lockWritableOwner(Connection connection, long actorId, long itemId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT seller_user_id,item_status,moderation_status FROM secondhand_items"
                        + " WHERE id=? FOR UPDATE")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != actorId
                        || !"AVAILABLE".equals(result.getString(2))
                        || !"VISIBLE".equals(result.getString(3))) {
                    throw new SecondhandRuleException("无权修改该二手物品图片");
                }
            }
        }
    }

    private static List<ImageRow> list(Connection connection, long itemId) throws SQLException {
        List<ImageRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,item_id,storage_key,thumbnail_storage_key,mime_type,byte_size,"
                        + "sha256,sort_order,is_cover,created_at FROM secondhand_item_images"
                        + " WHERE item_id=? ORDER BY sort_order,id")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(row(result));
            }
        }
        return List.copyOf(rows);
    }

    private static ImageRow row(ResultSet result) throws SQLException {
        return new ImageRow(result.getLong(1), result.getLong(2), result.getString(3),
                result.getString(4), result.getString(5), result.getLong(6),
                result.getString(7), result.getInt(8), result.getBoolean(9),
                result.getTimestamp(10).toInstant());
    }

    public record ImageRow(long id, long itemId, String storageKey,
                           String thumbnailStorageKey, String mimeType, long byteSize,
                           String sha256, int sortOrder, boolean cover, Instant createdAt) {
    }

    public record FinalizedUpload(String storageKey, String thumbnailStorageKey,
                                  String mimeType, long byteSize, String sha256) {
    }

    public record ImagePlan(List<ImagePlanItem> items) {
        public ImagePlan { items = List.copyOf(items); }
    }

    public record ImagePlanItem(Long existingImageId, String uploadId, boolean cover) {
    }

    public record CommitResult(List<ImageRow> rows, Set<String> deletedKeys) {
        public CommitResult {
            rows = List.copyOf(rows);
            deletedKeys = Set.copyOf(deletedKeys);
        }
    }
}
