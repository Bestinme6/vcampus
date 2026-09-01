package com.vcampus.server.database;

import com.vcampus.common.model.ShopCategory;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopStore.FinalizedUpload;
import com.vcampus.server.database.ShopStore.ImagePlan;
import com.vcampus.server.database.ShopStore.ImagePlanItem;
import com.vcampus.server.database.ShopStore.ProductInput;
import com.vcampus.server.model.ShopProductImageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopImageCommitTest {
    private ConnectionFactory connections;
    private ShopRepository repository;
    private long productId;
    private long otherProductId;
    private long firstImageId;
    private long secondImageId;
    private long foreignImageId;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        repository = new ShopRepository(connections, paymentWriter(), notificationWriter());
        productId = product("商品一");
        otherProductId = product("商品二");
        firstImageId = image(productId, "old-a.png", "old-a-thumb.png", 0, true);
        secondImageId = image(productId, "old-b.png", "old-b-thumb.png", 1, false);
        foreignImageId = image(otherProductId, "foreign.png", "foreign-thumb.png", 0, true);
    }

    @Test
    void reordersExistingImagesAndMovesTheSingleCover() throws Exception {
        var result = repository.replaceProductImages(9L, productId, Map.of(), new ImagePlan(List.of(
                new ImagePlanItem(secondImageId, null, true),
                new ImagePlanItem(firstImageId, null, false))));

        assertEquals(List.of(secondImageId, firstImageId), ids(result.images()));
        assertEquals(List.of(0, 1), result.images().stream()
                .map(ShopProductImageRecord::sortOrder).toList());
        assertEquals(List.of(true, false), result.images().stream()
                .map(ShopProductImageRecord::cover).toList());
        assertEquals(Set.of("old-a.png", "old-a-thumb.png", "old-b.png", "old-b-thumb.png"),
                result.keptKeys());
        assertEquals(Set.of(), result.deletedKeys());
    }

    @Test
    void replacesAnExistingImageAndReportsOnlyRemovedStorageKeys() throws Exception {
        FinalizedUpload uploaded = upload("upload-1", productId, "new.png", "new-thumb.png");

        var result = repository.replaceProductImages(9L, productId,
                Map.of(uploaded.uploadId(), uploaded), new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null, false),
                        new ImagePlanItem(null, uploaded.uploadId(), true))));

        assertEquals(List.of("old-a.png", "new.png"), result.images().stream()
                .map(ShopProductImageRecord::storageKey).toList());
        assertEquals(Set.of("old-b.png", "old-b-thumb.png"), result.deletedKeys());
        assertEquals(Set.of("old-a.png", "old-a-thumb.png", "new.png", "new-thumb.png"),
                result.keptKeys());
    }

    @Test
    void rejectsAPlanWithoutExactlyOneCoverAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                9L, productId, Map.of(), new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null, false),
                        new ImagePlanItem(secondImageId, null, false)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void rejectsDuplicateExistingImageIdsAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                9L, productId, Map.of(), new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null, true),
                        new ImagePlanItem(firstImageId, null, false)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void rejectsAnImageOwnedByAnotherProductAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                9L, productId, Map.of(), new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null, true),
                        new ImagePlanItem(foreignImageId, null, false)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void rejectsSixImagesAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);
        List<ImagePlanItem> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> new ImagePlanItem(null, "upload-" + index, index == 0))
                .toList();
        Map<String, FinalizedUpload> uploads = java.util.stream.IntStream.range(0, 6)
                .boxed().collect(java.util.stream.Collectors.toMap(
                        index -> "upload-" + index,
                        index -> upload("upload-" + index, productId,
                                "new-" + index + ".png", "thumb-" + index + ".png")));

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                9L, productId, uploads, new ImagePlan(six)));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void rejectsMissingFinalizedUploadAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                9L, productId, Map.of(), new ImagePlan(List.of(
                        new ImagePlanItem(null, "not-finalized", true)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void rejectsOrdinaryOperatorAndPreservesOriginalRows() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);

        assertThrows(ShopRuleException.class, () -> repository.replaceProductImages(
                1L, productId, Map.of(), new ImagePlan(List.of(
                        new ImagePlanItem(firstImageId, null, true)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void sqlFailureRollsBackEveryMetadataChange() throws Exception {
        List<ShopProductImageRecord> before = repository.productImages(productId);
        FinalizedUpload conflicting = upload(
                "upload-conflict", productId, "foreign.png", "other-new-thumb.png");

        assertThrows(java.sql.SQLException.class, () -> repository.replaceProductImages(
                9L, productId, Map.of(conflicting.uploadId(), conflicting),
                new ImagePlan(List.of(new ImagePlanItem(
                        null, conflicting.uploadId(), true)))));

        assertEquals(before, repository.productImages(productId));
    }

    @Test
    void emptyPlanRemovesAllImagesWithoutRequiringACover() throws Exception {
        var result = repository.replaceProductImages(
                10L, productId, Map.of(), new ImagePlan(List.of()));

        assertEquals(List.of(), repository.productImages(productId));
        assertEquals(Set.of("old-a.png", "old-a-thumb.png", "old-b.png", "old-b-thumb.png"),
                result.deletedKeys());
    }

    private FinalizedUpload upload(String uploadId, long targetProductId,
                                   String storageKey, String thumbnailKey) {
        return new FinalizedUpload(uploadId, targetProductId, storageKey, thumbnailKey,
                "image/png", 128L, "a".repeat(64));
    }

    private List<Long> ids(List<ShopProductImageRecord> images) {
        return images.stream().map(ShopProductImageRecord::id).toList();
    }

    private long product(String name) throws Exception {
        return repository.saveProduct(9L, new ProductInput(null, null, name, "说明",
                ShopCategory.OTHER, new BigDecimal("10.00"), true)).productId();
    }

    private long image(long targetProductId, String storageKey, String thumbnailKey,
                       int sortOrder, boolean cover) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO shop_product_images(product_id,storage_key,thumbnail_storage_key,"
                             + "mime_type,byte_size,sha256,sort_order,is_cover) VALUES(?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, targetProductId);
            statement.setString(2, storageKey);
            statement.setString(3, thumbnailKey);
            statement.setString(4, "image/png");
            statement.setLong(5, 100L);
            statement.setString(6, "b".repeat(64));
            statement.setInt(7, sortOrder);
            statement.setBoolean(8, cover);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void createSchema() throws Exception {
        String migration = Files.readString(Path.of("..", "database", "migrations", "008_shop.sql"));
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64),"
                    + " display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, role_code VARCHAR(64))");
            statement.execute("CREATE TABLE user_roles (user_id BIGINT, role_id BIGINT,"
                    + " PRIMARY KEY(user_id,role_id))");
            statement.execute("INSERT INTO users VALUES"
                    + "(1,'student','张同学',TRUE),(9,'shopadmin','商店管理员',TRUE),"
                    + "(10,'superadmin','超级管理员',TRUE)");
            statement.execute("INSERT INTO roles VALUES"
                    + "(1,'STUDENT'),(2,'SHOP_ADMIN'),(3,'SUPER_ADMIN')");
            statement.execute("INSERT INTO user_roles VALUES (1,1),(9,2),(10,3)");
            statement.execute(extractCreateTable(schema, "shop_products"));
            statement.execute(extractH2CreateTable(schema, "shop_product_images"));
            for (String table : new String[]{"shop_cart_items", "shop_orders",
                    "shop_order_items", "shop_inventory_movements"}) {
                statement.execute(extractCreateTable(migration, table));
            }
        }
    }

    private String extractCreateTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end + 1);
    }

    private String extractH2CreateTable(String sql, String table) {
        return extractCreateTable(sql, table).replace(") STORED", ")");
    }

    private BankPaymentWriter paymentWriter() {
        return new BankPaymentWriter() {
            @Override
            public PaymentResult debitForShop(Connection connection, long userId,
                                               BigDecimal amount, String referenceNo,
                                               String description) {
                return new PaymentResult(1L, BigDecimal.ZERO, false);
            }

            @Override
            public PaymentResult refundForShop(Connection connection, long userId,
                                                BigDecimal amount, String referenceNo,
                                                String description) {
                return new PaymentResult(1L, amount, false);
            }
        };
    }

    private NotificationWriter notificationWriter() {
        return new NotificationWriter() {
            @Override public void insert(Connection connection, NotificationWriter.NotificationDraft draft) { }
            @Override public void insertBatch(Connection connection,
                                              List<NotificationWriter.NotificationDraft> drafts) { }
        };
    }
}
