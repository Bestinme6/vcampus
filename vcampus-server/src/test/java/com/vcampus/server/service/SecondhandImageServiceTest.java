package com.vcampus.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.config.SecondhandImageConfig;
import com.vcampus.server.config.ShopImageConfig;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.SecondhandImageRepository;
import com.vcampus.server.database.SecondhandListingRepository;
import com.vcampus.server.database.SecondhandListingStore.ListingInput;
import com.vcampus.server.image.FileShopImageStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecondhandImageServiceTest {
    private SecondhandListingRepository listings;
    private SecondhandImageRepository metadata;
    private SessionManager sessions;
    private FakeImageStore files;
    private SecondhandImageService service;
    private String seller;
    private String other;
    private String admin;
    private long itemId;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:secondhand_images_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        createSchema(url);
        ConnectionFactory connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        listings = new SecondhandListingRepository(connections);
        metadata = new SecondhandImageRepository(connections);
        sessions = new SessionManager();
        seller = token(1, Set.of(UserRole.STUDENT));
        other = token(2, Set.of(UserRole.STUDENT));
        admin = token(9, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        itemId = listings.create(1, new ListingInput("旧教材", "九成新，含笔记", "BOOK",
                "九成新", new BigDecimal("12.00")));
        files = new FakeImageStore(itemId);
        service = new SecondhandImageService(listings, metadata, files, sessions);
    }

    @Test
    void onlyOwnerCanUploadAndCommitAndPublicReadStopsAfterModeration() throws Exception {
        assertFalse(service.uploadStart(request(Actions.SHOP_USED_IMAGE_UPLOAD_START, other,
                Map.of("itemId", Long.toString(itemId), "mimeType", "image/png",
                        "expectedBytes", "3"))).success());
        assertTrue(service.uploadStart(request(Actions.SHOP_USED_IMAGE_UPLOAD_START, seller,
                Map.of("itemId", Long.toString(itemId), "mimeType", "image/png",
                        "expectedBytes", "3"))).success());
        assertTrue(service.uploadComplete(request(Actions.SHOP_USED_IMAGE_UPLOAD_COMPLETE,
                seller, Map.of("uploadId", "up-1"))).success());
        assertFalse(service.commit(commit(other, RowCodec.encode("", "up-1", "true"))).success());
        assertTrue(service.commit(commit(seller, RowCodec.encode("", "up-1", "true"))).success());

        long imageId = metadata.list(itemId).getFirst().id();
        assertTrue(service.commit(commit(seller,
                RowCodec.encode(Long.toString(imageId), "", "true"))).success());
        assertEquals(imageId, metadata.list(itemId).getFirst().id());
        assertTrue(service.getChunk(request(Actions.SHOP_USED_IMAGE_GET_CHUNK, other,
                Map.of("imageId", Long.toString(imageId), "variant", "DETAIL",
                        "chunkIndex", "0"))).success());
        listings.moderate(9, itemId, false, "违规图片");
        assertFalse(service.getChunk(request(Actions.SHOP_USED_IMAGE_GET_CHUNK, other,
                Map.of("imageId", Long.toString(imageId), "variant", "DETAIL",
                        "chunkIndex", "0"))).success());
        assertTrue(service.getChunk(request(Actions.SHOP_USED_IMAGE_GET_CHUNK, admin,
                Map.of("imageId", Long.toString(imageId), "variant", "DETAIL",
                        "chunkIndex", "0"))).success());
    }

    @Test
    void dedicatedConfigurationAndRealStoreRejectNonImageContent(@TempDir Path temp) {
        assertTrue(SecondhandImageConfig.fromEnvironment(Map.of()).root().toString()
                .replace('\\', '/').endsWith("data/secondhand-images"));
        ShopImageConfig config = new ShopImageConfig(temp.resolve("used"), 1024, 128,
                1_000_000, java.time.Duration.ofMinutes(5));
        SecondhandImageService real = new SecondhandImageService(listings, metadata,
                new FileShopImageStore(config), sessions);
        var started = real.uploadStart(request(Actions.SHOP_USED_IMAGE_UPLOAD_START, seller,
                Map.of("itemId", Long.toString(itemId), "mimeType", "image/png",
                        "expectedBytes", "4")));
        String uploadId = started.data().get("uploadId");
        assertTrue(real.uploadChunk(request(Actions.SHOP_USED_IMAGE_UPLOAD_CHUNK, seller,
                Map.of("uploadId", uploadId, "chunkIndex", "0", "contentBase64",
                        Base64.getEncoder().encodeToString("text".getBytes())))).success());
        assertFalse(real.uploadComplete(request(Actions.SHOP_USED_IMAGE_UPLOAD_COMPLETE,
                seller, Map.of("uploadId", uploadId))).success());
    }

    private RequestMessage commit(String token, String... rows) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("itemId", Long.toString(itemId));
        values.put("itemCount", Integer.toString(rows.length));
        for (int index = 0; index < rows.length; index++) values.put("item." + index, rows[index]);
        return request(Actions.SHOP_USED_IMAGE_COMMIT, token, values);
    }

    private RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private String token(long id, Set<UserRole> roles) {
        return sessions.create(new UserAccount(id, "user" + id, "h", "s", "用户" + id,
                true, false, roles)).token();
    }

    private static void createSchema(String url) throws Exception {
        String sql = Files.readString(Path.of("..", "database", "migrations",
                "026_secondhand_market.sql"));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
            statement.execute(table(sql, "secondhand_items"));
            statement.execute(table(sql, "secondhand_item_images"));
            statement.execute(table(sql, "secondhand_listing_moderation_logs"));
            statement.execute("INSERT INTO users VALUES(1,'卖家'),(2,'其他用户'),(9,'管理员')");
        }
    }

    private static String table(String sql, String name) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + name);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private static final class FakeImageStore implements ShopImageStore {
        private final long itemId;
        FakeImageStore(long itemId) { this.itemId = itemId; }
        @Override public UploadTicket startUpload(long owner, long product, String mime, long bytes) {
            return new UploadTicket("up-1", product, bytes, 192 * 1024,
                    Instant.now().plusSeconds(600));
        }
        @Override public void appendChunk(long owner, String upload, int index, byte[] bytes) { }
        @Override public UploadedImage completeUpload(long owner, String upload) {
            return new UploadedImage(upload, itemId, "image/png", new byte[]{1, 2, 3},
                    "a".repeat(64), 1, 1);
        }
        @Override public FinalizedImage finalizeUpload(long owner, String upload) {
            return new FinalizedImage("used/full.png", "used/thumb.png");
        }
        @Override public InputStream open(String key) { return new ByteArrayInputStream(new byte[]{1, 2, 3}); }
        @Override public ImageDescriptor describe(String key) { return new ImageDescriptor(3, "a".repeat(64)); }
        @Override public ImageRange readRange(String key, long offset, int maxBytes) {
            return new ImageRange(new byte[]{1, 2, 3}, 3);
        }
        @Override public boolean deleteIfExists(String key) { return true; }
        @Override public void cleanup(Set<String> keys, Instant now) { }
    }
}
