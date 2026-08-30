package com.vcampus.server.database;

import com.vcampus.common.model.BankAccountStatus;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ShopStore.CheckoutResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCheckoutTransactionTest {
    private ConnectionFactory connections;
    private BankRepository bank;
    private ShopRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                "sa", ""));
        createSchema();
        NotificationWriter notifications = noOpNotifications();
        bank = new BankRepository(connections, notifications);
        repository = new ShopRepository(connections, bank, notifications);
    }

    @Test
    void checkoutUsesCurrentPricesAndCommitsEveryResource() throws Exception {
        long productId = product("SKU-1", "教材", "20.00", 5);
        repository.setCartQuantity(1L, productId, 2);
        repository.saveProduct(9L, new ProductInput(productId, "SKU-1", "教材", "新版",
                new BigDecimal("25.00"), true));
        bank.topUp(9L, "student1", new BigDecimal("100.00"), UUID.randomUUID().toString());

        CheckoutResult result = repository.checkout(1L, UUID.randomUUID().toString());

        assertEquals(ShopOrderStatus.PAID, result.status());
        assertEquals(new BigDecimal("50.00"), result.totalAmount());
        assertTrue(result.orderNo().matches("SO\\d{14}[0-9A-F]{12}"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_orders"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_order_items"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM shop_cart_items WHERE user_id=1"));
        assertEquals(3, scalarInt("SELECT stock FROM shop_products WHERE id=" + productId));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_PAYMENT'"));
        assertEquals(new BigDecimal("50.00"), bank.account(1L).balance());
    }

    @Test
    void repeatedOperationReturnsOriginalOrderWithoutSecondDebit() throws Exception {
        long productId = product("SKU-2", "水杯", "30.00", 2);
        repository.setCartQuantity(1L, productId, 1);
        bank.topUp(9L, "student1", new BigDecimal("100.00"), UUID.randomUUID().toString());
        String operationId = UUID.randomUUID().toString();

        CheckoutResult first = repository.checkout(1L, operationId);
        CheckoutResult second = repository.checkout(1L, operationId);

        assertEquals(first.orderNo(), second.orderNo());
        assertTrue(second.duplicate());
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_orders"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_PAYMENT'"));
    }

    @Test
    void emptyDisabledOutOfStockInsufficientAndFrozenCartsDoNotPartiallyCommit() throws Exception {
        assertThrows(ShopRuleException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));

        long productId = product("SKU-3", "校徽", "20.00", 1);
        repository.setCartQuantity(1L, productId, 2);
        bank.topUp(9L, "student1", new BigDecimal("10.00"), UUID.randomUUID().toString());
        assertThrows(ShopRuleException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));
        assertUnchanged(productId, 1, 2);

        repository.setCartQuantity(1L, productId, 1);
        assertThrows(BankRuleException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));
        assertUnchanged(productId, 1, 1);

        bank.topUp(9L, "student1", new BigDecimal("20.00"), UUID.randomUUID().toString());
        bank.setStatus(9L, 1L, BankAccountStatus.FROZEN);
        assertThrows(BankRuleException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));
        assertUnchanged(productId, 1, 1);

        bank.setStatus(9L, 1L, BankAccountStatus.ACTIVE);
        repository.setProductEnabled(9L, productId, false);
        assertThrows(ShopRuleException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));
        assertUnchanged(productId, 1, 1);
    }

    @Test
    void paymentWriterFailureRollsBackOrderStockAndCart() throws Exception {
        long productId = product("SKU-4", "笔记本", "15.00", 2);
        repository.setCartQuantity(1L, productId, 1);
        ShopRepository failing = new ShopRepository(connections, failingPayment(), noOpNotifications());

        assertThrows(SQLException.class,
                () -> failing.checkout(1L, UUID.randomUUID().toString()));

        assertUnchanged(productId, 2, 1);
    }

    @Test
    void orderItemPersistenceFailureRollsBackEarlierBankAndInventoryWrites() throws Exception {
        long productId = product("SKU-4B", "文件夹", "12.00", 2);
        repository.setCartQuantity(1L, productId, 1);
        bank.topUp(9L, "student1", new BigDecimal("50.00"), UUID.randomUUID().toString());
        execute("DROP TABLE shop_order_items");

        assertThrows(SQLException.class,
                () -> repository.checkout(1L, UUID.randomUUID().toString()));

        assertUnchanged(productId, 2, 1);
        assertEquals(new BigDecimal("50.00"), bank.account(1L).balance());
    }

    @Test
    void concurrentBuyersCompetingForFinalUnitProduceOnePaidOrder() throws Exception {
        long productId = product("SKU-5", "限量纪念章", "40.00", 1);
        repository.setCartQuantity(1L, productId, 1);
        repository.setCartQuantity(2L, productId, 1);
        bank.topUp(9L, "student1", new BigDecimal("100.00"), UUID.randomUUID().toString());
        bank.topUp(9L, "student2", new BigDecimal("100.00"), UUID.randomUUID().toString());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> checkoutAfterBarrier(1L, ready, start));
            var second = executor.submit(() -> checkoutAfterBarrier(2L, ready, start));
            ready.await(); start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM shop_orders WHERE status='PAID'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SHOP_PAYMENT'"));
        assertEquals(0, scalarInt("SELECT stock FROM shop_products WHERE id=" + productId));
    }

    private boolean checkoutAfterBarrier(long userId, CountDownLatch ready,
                                         CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        try {
            repository.checkout(userId, UUID.randomUUID().toString());
            return true;
        } catch (ShopRuleException expected) {
            return false;
        }
    }

    private void assertUnchanged(long productId, int stock, int cartQuantity) throws Exception {
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM shop_orders"));
        assertEquals(stock, scalarInt("SELECT stock FROM shop_products WHERE id=" + productId));
        assertEquals(cartQuantity, scalarInt("SELECT quantity FROM shop_cart_items"
                + " WHERE user_id=1 AND product_id=" + productId));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM bank_ledger_entries"
                + " WHERE entry_type='SHOP_PAYMENT'"));
    }

    private long product(String sku, String name, String price, int stock) throws Exception {
        long id = repository.saveProduct(9L, new ProductInput(null, sku, name, "说明",
                new BigDecimal(price), true)).productId();
        repository.adjustInventory(9L, id, stock, "首次入库");
        return id;
    }

    private void createSchema() throws Exception {
        String bankSql = Files.readString(Path.of("..", "database", "migrations", "007_bank.sql"));
        String shopSql = Files.readString(Path.of("..", "database", "migrations", "008_shop.sql"));
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(64),"
                    + " display_name VARCHAR(100), enabled BOOLEAN)");
            statement.execute("INSERT INTO users VALUES"
                    + "(1,'student1','张同学',TRUE),(2,'student2','李同学',TRUE),"
                    + "(9,'admin','管理员',TRUE)");
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

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private NotificationWriter noOpNotifications() {
        return new NotificationWriter() {
            @Override public void insert(Connection connection, NotificationDraft draft) { }
            @Override public void insertBatch(Connection connection, List<NotificationDraft> drafts) { }
        };
    }

    private BankPaymentWriter failingPayment() {
        return new BankPaymentWriter() {
            @Override public PaymentResult debitForShop(Connection connection, long userId,
                    BigDecimal amount, String referenceNo, String description) throws SQLException {
                throw new SQLException("payment failed");
            }
            @Override public PaymentResult refundForShop(Connection connection, long userId,
                    BigDecimal amount, String referenceNo, String description) {
                return new PaymentResult(1L, amount, false);
            }
        };
    }
}
