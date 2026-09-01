package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopStore.ProductInput;
import com.vcampus.server.database.ShopStore.ProductQuery;
import com.vcampus.server.database.ShopStore.OrderQuery;
import com.vcampus.common.model.ShopCategory;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.ShopProductSort;
import com.vcampus.server.model.ShopProductImageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRepositoryTest {
    private ConnectionFactory connections;
    private ShopRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        repository = new ShopRepository(connections, paymentWriter(), notificationWriter());
    }

    @Test
    void cartPersistsOnlyProductAndQuantityAndUsesCurrentPrice() throws Exception {
        long productId = save("SKU-1", "教材", "20.00", true);
        repository.adjustInventory(9L, productId, 5, "首次入库");
        repository.setCartQuantity(1L, productId, 2);
        repository.saveProduct(9L, new ProductInput(
                productId, "SKU-1", "教材", "新版", ShopCategory.LEARNING_STATIONERY,
                new BigDecimal("25.00"), true));

        ShopRepository reopened = new ShopRepository(
                connections, paymentWriter(), notificationWriter());
        var cart = reopened.cart(1L);

        assertEquals(new BigDecimal("50.00"), cart.estimatedTotal());
        assertEquals(2, cart.rows().getFirst().quantity());
        assertEquals(new BigDecimal("25.00"), cart.rows().getFirst().unitPrice());
    }

    @Test
    void inventoryAdjustmentCannotMakeStockNegativeAndWritesMovement() throws Exception {
        long productId = save("SKU-2", "笔记本", "8.00", true);
        repository.adjustInventory(9L, productId, 5, "首次入库");

        assertThrows(ShopRuleException.class,
                () -> repository.adjustInventory(9L, productId, -6, "盘点"));
        assertEquals(5, repository.searchProducts(
                new ProductQuery("笔记本", null, null, ShopProductSort.NEWEST, 1, 10))
                .rows().getFirst().stock());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_inventory_movements"));
    }

    @Test
    void ordinarySearchHidesDisabledProductsWhileAdminCanSeeThem() throws Exception {
        save("SKU-3", "在售商品", "10.00", true);
        long disabledId = save("SKU-4", "下架商品", "10.00", false);

        var ordinary = repository.searchProducts(new ProductQuery(
                "", null, true, ShopProductSort.NEWEST, 1, 10));
        var admin = repository.searchProducts(new ProductQuery(
                "", null, null, ShopProductSort.NEWEST, 1, 10));

        assertEquals(1, ordinary.total());
        assertEquals(2, admin.total());
        assertFalse(admin.rows().stream().filter(row -> row.id() == disabledId)
                .findFirst().orElseThrow().enabled());
    }

    @Test
    void zeroQuantityRemovesCartRow() throws Exception {
        long productId = save("SKU-5", "校徽", "6.00", true);
        repository.setCartQuantity(1L, productId, 3);
        assertEquals(1, repository.cart(1L).rows().size());

        repository.setCartQuantity(1L, productId, 0);

        assertTrue(repository.cart(1L).rows().isEmpty());
    }

    @Test
    void disablingAndRemovingCartItemArePersistent() throws Exception {
        long productId = save("SKU-6", "水杯", "18.00", true);
        repository.adjustInventory(9L, productId, 2, "入库");
        repository.setCartQuantity(1L, productId, 1);
        assertTrue(repository.setProductEnabled(9L, productId, false));
        assertFalse(repository.cart(1L).rows().getFirst().enabled());

        repository.removeCartItem(1L, productId);

        assertTrue(repository.cart(1L).rows().isEmpty());
    }

    @Test
    void newProductsReceiveSequentialSkuRegardlessOfClientValue() throws Exception {
        long firstId = repository.saveProduct(9L, new ProductInput(
                null, "CLIENT-SUPPLIED", "教材", "说明", ShopCategory.LEARNING_STATIONERY,
                new BigDecimal("20.00"), true)).productId();
        long secondId = repository.saveProduct(9L, new ProductInput(
                null, "ANOTHER-VALUE", "水杯", "说明", ShopCategory.DAILY_SUPPLIES,
                new BigDecimal("30.00"), true)).productId();

        assertEquals("SKU-000001", repository.searchProducts(
                new ProductQuery("教材", null, null, ShopProductSort.NEWEST, 1, 10))
                .rows().getFirst().sku());
        assertEquals("SKU-000002", repository.searchProducts(
                new ProductQuery("水杯", null, null, ShopProductSort.NEWEST, 1, 10))
                .rows().getFirst().sku());
        assertEquals(1L, firstId);
        assertEquals(2L, secondId);
    }

    @Test
    void editingProductCannotChangeGeneratedSku() throws Exception {
        long productId = repository.saveProduct(9L, new ProductInput(
                null, "IGNORED", "教材", "说明", ShopCategory.LEARNING_STATIONERY,
                new BigDecimal("20.00"), true))
                .productId();

        repository.saveProduct(9L, new ProductInput(productId, "HACKED-SKU",
                "新版教材", "新版说明", ShopCategory.DIGITAL_ACCESSORIES,
                new BigDecimal("25.00"), true));

        var edited = repository.searchProducts(
                new ProductQuery("新版教材", null, null, ShopProductSort.NEWEST, 1, 10))
                .rows().getFirst();
        assertEquals("SKU-000001", edited.sku());
        assertEquals(ShopCategory.DIGITAL_ACCESSORIES, edited.category());
        assertEquals(new BigDecimal("25.00"), edited.price());
    }

    @Test
    void categoryFilterRunsBeforeStablePricePagination() throws Exception {
        long first = save("SKU-A", "Merch A", ShopCategory.CAMPUS_MERCH, "10.00", true);
        long second = save("SKU-B", "Merch B", ShopCategory.CAMPUS_MERCH, "10.00", true);
        save("SKU-C", "Daily", ShopCategory.DAILY_SUPPLIES, "1.00", true);

        var firstPage = repository.searchProducts(new ProductQuery("", ShopCategory.CAMPUS_MERCH,
                true, ShopProductSort.PRICE_ASC, 1, 1));
        var secondPage = repository.searchProducts(new ProductQuery("", ShopCategory.CAMPUS_MERCH,
                true, ShopProductSort.PRICE_ASC, 2, 1));

        assertEquals(2, firstPage.total());
        assertEquals(List.of(first), ids(firstPage));
        assertEquals(List.of(second), ids(secondPage));
    }

    @Test
    void productSortsUseDeterministicIdTieBreakers() throws Exception {
        long first = save("SKU-S1", "Alpha", ShopCategory.OTHER, "10.00", true);
        long second = save("SKU-S2", "Alpha", ShopCategory.OTHER, "10.00", true);
        long third = save("SKU-S3", "Zulu", ShopCategory.OTHER, "5.00", true);
        execute("UPDATE shop_products SET created_at='2026-09-01 08:00:00'");

        assertEquals(List.of(third, second, first), ids(search(ShopProductSort.NEWEST)));
        assertEquals(List.of(third, first, second), ids(search(ShopProductSort.PRICE_ASC)));
        assertEquals(List.of(second, first, third), ids(search(ShopProductSort.PRICE_DESC)));
        assertEquals(List.of(first, second, third), ids(search(ShopProductSort.NAME_ASC)));
    }

    @Test
    void productDetailRespectsIncludeDisabled() throws Exception {
        long productId = save("SKU-DISABLED", "Disabled", ShopCategory.OTHER, "9.00", false);

        assertThrows(ShopRuleException.class, () -> repository.product(productId, false));

        var detail = repository.product(productId, true);
        assertEquals(productId, detail.product().id());
        assertFalse(detail.product().enabled());
        assertTrue(detail.images().isEmpty());
    }

    @Test
    void productImagesReturnEveryFieldInStableDisplayOrder() throws Exception {
        long productId = save("SKU-IMAGES", "Images", ShopCategory.DIGITAL_ACCESSORIES,
                "99.00", true);
        execute("INSERT INTO shop_product_images"
                + "(id,product_id,storage_key,thumbnail_storage_key,mime_type,byte_size,sha256,"
                + "sort_order,is_cover,created_at,updated_at) VALUES"
                + "(11," + productId + ",'full-b','thumb-b','image/png',2048,'"
                + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',"
                + "1,FALSE,'2026-09-01 08:02:00','2026-09-01 08:03:00'),"
                + "(12," + productId + ",'full-a','thumb-a','image/jpeg',1024,'"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',"
                + "0,TRUE,'2026-09-01 08:00:00','2026-09-01 08:01:00')");

        assertEquals(List.of(
                new ShopProductImageRecord(12L, productId, "full-a", "thumb-a", "image/jpeg",
                        1024L,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        0, true, Timestamp.valueOf("2026-09-01 08:00:00").toInstant(),
                        Timestamp.valueOf("2026-09-01 08:01:00").toInstant()),
                new ShopProductImageRecord(11L, productId, "full-b", "thumb-b", "image/png",
                        2048L,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        1, false, Timestamp.valueOf("2026-09-01 08:02:00").toInstant(),
                        Timestamp.valueOf("2026-09-01 08:03:00").toInstant())),
                repository.productImages(productId));
    }

    @Test
    void administrativeOrderSearchMatchesOrderNumberOrBuyerUsername() throws Exception {
        execute("INSERT INTO shop_orders(order_no,buyer_user_id,checkout_operation_id,total_amount,status)"
                + " VALUES ('SO-STUDENT-001',1,'op-student',20.00,'PAID'),"
                + "('SO-TEACHER-002',2,'op-teacher',30.00,'SHIPPED')");

        var byUsername = repository.searchOrders(
                new OrderQuery(null, "student", null, 1, 10));
        var byOrderNo = repository.searchOrders(
                new OrderQuery(null, "TEACHER-002", ShopOrderStatus.SHIPPED, 1, 10));

        assertEquals(1, byUsername.total());
        assertEquals("student", byUsername.rows().getFirst().buyerUsername());
        assertEquals(1, byOrderNo.total());
        assertEquals("SO-TEACHER-002", byOrderNo.rows().getFirst().orderNo());
    }

    private long save(String sku, String name, String price, boolean enabled) throws Exception {
        return save(sku, name, ShopCategory.OTHER, price, enabled);
    }

    private long save(String sku, String name, ShopCategory category, String price,
                      boolean enabled) throws Exception {
        return repository.saveProduct(9L, new ProductInput(
                null, sku, name, "说明", category, new BigDecimal(price), enabled)).productId();
    }

    private ShopStore.ProductPage search(ShopProductSort sort) throws SQLException {
        return repository.searchProducts(new ProductQuery("", null, true, sort, 1, 10));
    }

    private List<Long> ids(ShopStore.ProductPage page) {
        return page.rows().stream().map(row -> row.id()).toList();
    }

    private void createSchema() throws Exception {
        String migration = Files.readString(
                Path.of("..", "database", "migrations", "008_shop.sql"));
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64),"
                    + " display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("INSERT INTO users VALUES"
                    + "(1,'student','张同学',TRUE),(2,'teacher','李老师',TRUE),"
                    + "(9,'shopadmin','商店管理员',TRUE)");
            statement.execute(extractCreateTable(schema, "shop_products"));
            statement.execute(extractCreateTable(schema, "shop_product_images"));
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

    private int scalarInt(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
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
            @Override public void insert(Connection connection, NotificationDraft draft) { }
            @Override public void insertBatch(Connection connection,
                                              java.util.List<NotificationDraft> drafts) { }
        };
    }
}
