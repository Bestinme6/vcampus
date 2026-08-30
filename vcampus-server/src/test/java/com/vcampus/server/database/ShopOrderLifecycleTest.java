package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopStore.OrderQuery;
import com.vcampus.server.database.ShopStore.ProductInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopOrderLifecycleTest {
    private ConnectionFactory connections;
    private BankRepository bank;
    private ShopRepository repository;
    private long productId;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        NotificationRepository notifications = new NotificationRepository(connections);
        bank = new BankRepository(connections, notifications);
        repository = new ShopRepository(connections, bank, notifications);
        productId = repository.saveProduct(9L, new ProductInput(null, "SKU-LIFE", "教材",
                "说明", new BigDecimal("30.00"), true)).productId();
        repository.adjustInventory(9L, productId, 3, "首次入库");
        bank.topUp(9L, "student1", new BigDecimal("100.00"), UUID.randomUUID().toString());
    }

    @Test
    void cancellingPaidOrderRefundsRestocksAndChangesStateAtomically() throws Exception {
        long orderId = paidOrder();
        bank.setStatus(9L, "student1", BankAccountStatus.FROZEN);

        var cancelled = repository.cancelOrder(1L, orderId);
        var duplicate = repository.cancelOrder(1L, orderId);

        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertEquals(ShopOrderStatus.CANCELLED, duplicate.status());
        assertEquals(new BigDecimal("100.00"), bank.account(1L).balance());
        assertEquals(3, scalarInt("SELECT stock FROM shop_products WHERE id=" + productId));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_REFUND'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_inventory_movements WHERE movement_type='ORDER_CANCEL'"));
    }

    @Test
    void shippingNotifiesBuyerAndConfirmationCompletesOrder() throws Exception {
        long orderId = paidOrder();

        var shipped = repository.shipOrder(9L, orderId);
        var duplicate = repository.shipOrder(9L, orderId);
        var completed = repository.confirmOrder(1L, orderId);

        assertEquals(ShopOrderStatus.SHIPPED, shipped.status());
        assertEquals(ShopOrderStatus.SHIPPED, duplicate.status());
        assertEquals(ShopOrderStatus.COMPLETED, completed.status());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications"
                + " WHERE notification_type='SHOP_ORDER_SHIPPED'"));
    }

    @Test
    void ownershipAndStateRulesProtectOrders() throws Exception {
        long orderId = paidOrder();
        assertThrows(ShopRuleException.class, () -> repository.order(2L, orderId, false));
        assertThrows(ShopRuleException.class, () -> repository.cancelOrder(2L, orderId));
        assertEquals(orderId, repository.order(9L, orderId, true).order().id());

        repository.shipOrder(9L, orderId);
        assertThrows(ShopRuleException.class, () -> repository.cancelOrder(1L, orderId));
        assertThrows(ShopRuleException.class, () -> repository.confirmOrder(2L, orderId));
    }

    @Test
    void notificationFailureRollsShippingBackToPaid() throws Exception {
        long orderId = paidOrder();
        ShopRepository failing = new ShopRepository(connections, bank, failingNotifications());

        assertThrows(SQLException.class, () -> failing.shipOrder(9L, orderId));

        assertEquals(ShopOrderStatus.PAID,
                repository.order(1L, orderId, false).order().status());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM notifications"
                + " WHERE notification_type='SHOP_ORDER_SHIPPED'"));
    }

    @Test
    void searchesAndDetailsReturnImmutableSnapshots() throws Exception {
        long orderId = paidOrder();
        repository.saveProduct(9L, new ProductInput(productId, "SKU-LIFE", "新版教材",
                "新说明", new BigDecimal("50.00"), true));

        var page = repository.searchOrders(new OrderQuery(
                1L, "", ShopOrderStatus.PAID, 1, 10));
        var detail = repository.order(1L, orderId, false);

        assertEquals(1, page.total());
        assertEquals("教材", detail.items().getFirst().nameSnapshot());
        assertEquals(new BigDecimal("30.00"), detail.items().getFirst().unitPrice());
    }

    private long paidOrder() throws Exception {
        repository.setCartQuantity(1L, productId, 1);
        return repository.checkout(1L, UUID.randomUUID().toString()).orderId();
    }

    private void createSchema() throws Exception {
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        String bankSql = Files.readString(Path.of("..", "database", "migrations", "007_bank.sql"));
        String shopSql = Files.readString(Path.of("..", "database", "migrations", "008_shop.sql"));
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64),"
                    + " display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("INSERT INTO users VALUES"
                    + "(1,'student1','张同学',TRUE),(2,'student2','李同学',TRUE),"
                    + "(9,'admin','管理员',TRUE)");
            statement.execute(extract(schema, "notifications"));
            statement.execute(extract(bankSql, "bank_accounts"));
            statement.execute(extract(bankSql, "bank_ledger_entries"));
            for (String table : List.of("shop_products", "shop_cart_items", "shop_orders",
                    "shop_order_items", "shop_inventory_movements")) statement.execute(extract(shopSql, table));
        }
    }

    private String extract(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private int scalarInt(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next(); return result.getInt(1);
        }
    }

    private NotificationWriter failingNotifications() {
        return new NotificationWriter() {
            @Override public void insert(Connection connection, NotificationDraft draft)
                    throws SQLException { throw new SQLException("notification failed"); }
            @Override public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                    throws SQLException { throw new SQLException("notification failed"); }
        };
    }
}
