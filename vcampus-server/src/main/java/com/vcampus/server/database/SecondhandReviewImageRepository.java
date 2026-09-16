package com.vcampus.server.database;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SecondhandReviewImageRepository {
    private final ConnectionFactory connections;

    public SecondhandReviewImageRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public Optional<ImageFile> find(long imageId, boolean administrator) throws SQLException {
        if (imageId < 1) throw new IllegalArgumentException("图片ID无效");
        String visibility = administrator ? "" : " AND r.status='NORMAL'";
        try (var connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT i.id,i.storage_key,i.thumbnail_storage_key,i.mime_type,"
                             + "i.byte_size,i.sha256 FROM secondhand_review_images i "
                             + "JOIN secondhand_reviews r ON r.id=i.review_id WHERE i.id=?"
                             + visibility)) {
            statement.setLong(1, imageId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ImageFile(result.getLong(1),
                        result.getString(2), result.getString(3), result.getString(4),
                        result.getLong(5), result.getString(6))) : Optional.empty();
            }
        }
    }

    public Set<String> storageKeys() throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (var connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT storage_key,thumbnail_storage_key FROM secondhand_review_images");
             var result = statement.executeQuery()) {
            while (result.next()) { keys.add(result.getString(1)); keys.add(result.getString(2)); }
        }
        return Set.copyOf(keys);
    }

    public record ImageFile(long id, String storageKey, String thumbnailStorageKey,
                            String mimeType, long byteSize, String sha256) { }
}
