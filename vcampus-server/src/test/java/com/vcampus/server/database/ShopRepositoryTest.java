package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopStore.ProductInput;
import com.vcampus.server.database.ShopStore.ProductQuery;
import com.vcampus.server.database.ShopStore.OrderQuery;
import com.vcampus.common.model.ShopOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
                productId, "SKU-1", "教材", "新版", new BigDecimal("25.00"), true));

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
                new ProductQuery("SKU-2", null, 1, 10)).rows().getFirst().stock());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_inventory_movements"));
    }

    @Test
    void ordinarySearchHidesDisabledProductsWhileAdminCanSeeThem() throws Exception {
        save("SKU-3", "在售商品", "10.00", true);
        long disabledId = save("SKU-4", "下架商品", "10.00", false);

        var ordinary = repository.searchProducts(new ProductQuery("", true, 1, 10));
        var admin = repository.searchProducts(new ProductQuery("", null, 1, 10));

        assertEquals(1, ordinary.total());
        assertEquals(2, admin.total());
        assertFalse(admin.rows().stream().filter(row -> row.id() == disabledId)
                .findFirst().orElseThrow().enabled());
    }

    @Test
    void skuIsUniqueAndZeroQuantityRemovesCartRow() throws Exception {
        long productId = save("SKU-5", "校徽", "6.00", true);
        assertThrows(SQLException.class, () -> save("SKU-5", "重复校徽", "7.00", true));
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
        return repository.saveProduct(9L, new ProductInput(
                null, sku, name, "说明", new BigDecimal(price), enabled)).productId();
    }

    private void createSchema() throws Exception {
        String migration = Files.readString(
                Path.of("..", "database", "migrations", "008_shop.sql"));
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64),"
                    + " display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("INSERT INTO users VALUES"
                    + "(1,'student','张同学',TRUE),(2,'teacher','李老师',TRUE),"
                    + "(9,'shopadmin','商店管理员',TRUE)");
            for (String table : new String[]{"shop_products", "shop_cart_items", "shop_orders",
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
