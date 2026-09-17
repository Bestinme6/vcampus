package com.vcampus.server.database;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandOrderTransactionTest {
    private ConnectionFactory connections;
    private BankRepository bank;
    private SecondhandOrderRepository orders;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        execute("INSERT INTO users VALUES (1,'seller','卖家',TRUE),(2,'buyer','买家',TRUE),(3,'other','其他买家',TRUE),(9,'admin','管理员',TRUE)");
        var notifications = new NotificationRepository(connections);
        bank = new BankRepository(connections, notifications);
        bank.topUp(9L, "buyer", new BigDecimal("50.00"), UUID.randomUUID().toString());
        bank.topUp(9L, "other", new BigDecimal("50.00"), UUID.randomUUID().toString());
        var addresses = new ShopAddressRepository(connections);
        orders = new SecondhandOrderRepository(connections, bank, addresses, notifications);
        execute("INSERT INTO shop_shipping_addresses(user_id,slot,version_token,recipient,phone,address_text) VALUES(2,1,'v1','买家','13800000000','江苏省南京市测试路1号')");
    }

    @Test
    void adminSearchSupportsEmptyKeywordStatusPagingAndLiteralWildcards() throws Exception {
        long special = item("教材100%_!\\", "12.00");
        long plain = item("普通教材", "10.00");
        var address = new ShippingAddressChoice(1,"v1",null);
        var first = orders.buy(2L,special,UUID.randomUUID().toString(),address);
        orders.buy(2L,plain,UUID.randomUUID().toString(),address);
        orders.ship(1L,first.orderId());
        assertEquals(2,orders.searchAdmin("",null,1,12).total());
        assertEquals(1,orders.searchAdmin("",null,2,1).rows().size());
        assertEquals(1,orders.searchAdmin("",ShopOrderStatus.SHIPPED,1,12).total());
        for(String keyword:List.of("%","_","!","\\","100%_!\\")) {
            var page=orders.searchAdmin(keyword,null,1,12);
            assertEquals(1,page.total(),keyword);
            assertEquals(first.orderId(),page.rows().getFirst().id());
        }
        assertEquals(0,orders.searchAdmin("不存在",null,1,12).total());
    }

    @Test
    void retryReturnsOriginalSnapshotWithoutSecondHold() throws Exception {
        long item = item("旧教材", "12.00");
        String operation = UUID.randomUUID().toString();
        var address = new ShippingAddressChoice(1, "v1", null);

        var first = orders.buy(2L, item, operation, address);
        execute("UPDATE shop_shipping_addresses SET recipient='新收件人',version_token='v2' WHERE user_id=2 AND slot=1");
        var retry = orders.buy(2L, item, operation, new ShippingAddressChoice(1, "v1", null));

        assertEquals(first.orderId(), retry.orderId());
        assertTrue(retry.duplicate());
        assertEquals("买家", orders.order(2L, first.orderId(), false).shippingAddress().recipient());
        assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_escrows WHERE order_id=" + first.orderId()));
        assertEquals(1, scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SECONDHAND_HOLD'"));
        assertEquals(new BigDecimal("38.00"), bank.account(2L).balance());
    }

    @Test
    void onlyPartiesCanAdvanceOrderAndConfirmationPaysFrozenSeller() throws Exception {
        long item = item("旧教材", "12.00");
        var bought = orders.buy(2L, item, UUID.randomUUID().toString(), inlineAddress());

        assertThrows(SecondhandRuleException.class, () -> orders.ship(3L, bought.orderId()));
        assertEquals(ShopOrderStatus.SHIPPED, orders.ship(1L, bought.orderId()).status());
        bank.setStatus(9L, "seller", com.vcampus.common.model.BankAccountStatus.FROZEN);
        assertThrows(SecondhandRuleException.class, () -> orders.confirm(3L, bought.orderId()));
        assertEquals(ShopOrderStatus.COMPLETED, orders.confirm(2L, bought.orderId()).status());
        assertEquals(new BigDecimal("12.00"), bank.account(1L).balance());
        assertEquals("SETTLED", scalarString("SELECT status FROM secondhand_escrows WHERE order_id=" + bought.orderId()));
    }

    @Test
    void cancellationRefundsBuyerAndRelistsVisibleItem() throws Exception {
        long item = item("旧台灯", "8.00");
        var bought = orders.buy(2L, item, UUID.randomUUID().toString(), inlineAddress());

        var cancelled = orders.cancel(2L, bought.orderId());

        assertEquals(ShopOrderStatus.CANCELLED, cancelled.status());
        assertEquals(new BigDecimal("50.00"), bank.account(2L).balance());
        assertEquals("REFUNDED", scalarString("SELECT status FROM secondhand_escrows WHERE order_id=" + bought.orderId()));
        assertEquals("AVAILABLE", scalarString("SELECT item_status FROM secondhand_items WHERE id=" + item));
    }

    @Test
    void selfPurchaseAndStaleAddressLeaveNoOrderOrHold() throws Exception {
        long item = item("旧教材", "12.00");
        assertThrows(SecondhandRuleException.class, () -> orders.buy(
                1L, item, UUID.randomUUID().toString(), inlineAddress()));
        assertThrows(IllegalArgumentException.class, () -> orders.buy(2L, item,
                UUID.randomUUID().toString(), new ShippingAddressChoice(1, "stale", null)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM secondhand_orders"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SECONDHAND_HOLD'"));
    }

    @Test
    void operationIdCannotRevealAnotherBuyersOrder() throws Exception {
        long item = item("旧教材", "12.00");
        String operation = UUID.randomUUID().toString();
        orders.buy(2L, item, operation, inlineAddress());

        assertThrows(SecondhandRuleException.class,
                () -> orders.buy(3L, item, operation, inlineAddress()));
    }

    @Test
    void frozenOrUnderfundedBuyerLeavesListingAvailable() throws Exception {
        long frozenItem = item("旧教材", "12.00");
        bank.setStatus(9L, "buyer", com.vcampus.common.model.BankAccountStatus.FROZEN);
        assertThrows(BankRuleException.class, () -> orders.buy(
                2L, frozenItem, UUID.randomUUID().toString(), inlineAddress()));
        assertEquals("AVAILABLE", scalarString(
                "SELECT item_status FROM secondhand_items WHERE id=" + frozenItem));

        long expensive = item("旧电脑", "80.00");
        assertThrows(BankRuleException.class, () -> orders.buy(
                3L, expensive, UUID.randomUUID().toString(), inlineAddress()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM secondhand_orders"));
    }

    @Test
    void adminResolutionIsIdempotentAndRequiresShipmentBeforeSettlement() throws Exception {
        long item = item("旧教材", "12.00");
        var bought = orders.buy(2L, item, UUID.randomUUID().toString(), inlineAddress());

        assertThrows(SecondhandRuleException.class, () -> orders.resolve(9L, bought.orderId(),
                SecondhandOrderStore.Resolution.SETTLE, UUID.randomUUID().toString(), "提前结算卖家"));
        String operation = UUID.randomUUID().toString();
        var first = orders.resolve(9L, bought.orderId(), SecondhandOrderStore.Resolution.REFUND,
                operation, "买卖双方协商退款");
        var retry = orders.resolve(9L, bought.orderId(), SecondhandOrderStore.Resolution.REFUND,
                operation, "重试不应重复退款");

        assertEquals(ShopOrderStatus.CANCELLED, first.status());
        assertEquals(first.id(), retry.id());
        assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_order_resolution_logs"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM bank_ledger_entries WHERE entry_type='SECONDHAND_REFUND'"));
    }

    @Test
    void notificationFailureRollsBackOrderHoldAndReservation() throws Exception {
        var failing = new NotificationWriter() {
            @Override public void insert(Connection connection, NotificationDraft draft) throws SQLException {
                throw new SQLException("notification failed");
            }
            @Override public void insertBatch(Connection connection, List<NotificationDraft> drafts)
                    throws SQLException { throw new SQLException("notification failed"); }
        };
        var failingOrders = new SecondhandOrderRepository(connections, bank,
                new ShopAddressRepository(connections), failing);
        long item = item("旧教材", "12.00");

        assertThrows(SQLException.class, () -> failingOrders.buy(
                2L, item, UUID.randomUUID().toString(), inlineAddress()));
        assertEquals(new BigDecimal("50.00"), bank.account(2L).balance());
        assertEquals("AVAILABLE", scalarString("SELECT item_status FROM secondhand_items WHERE id=" + item));
        assertEquals(0, scalar("SELECT COUNT(*) FROM secondhand_orders"));
    }

    @Test
    void concurrentBuyersCannotBuyTheSameItem() throws Exception {
        long item = item("限量旧教材", "12.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> buyAfterBarrier(ready, start, 2L, item));
            var second = executor.submit(() -> buyAfterBarrier(ready, start, 3L, item));
            ready.await();
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_orders WHERE item_id=" + item));
        assertEquals(1, scalar("SELECT COUNT(*) FROM secondhand_escrows"));
    }

    private long item(String title, String price) throws SQLException {
        execute("INSERT INTO secondhand_items(seller_user_id,title,description,category,condition_text,price) VALUES(1,'"
                + title + "','描述完整','OTHER','九成新'," + price + ")");
        return scalar("SELECT MAX(id) FROM secondhand_items");
    }

    private static ShippingAddressChoice inlineAddress() {
        return new ShippingAddressChoice(null, null,
                new ShippingAddressValue("买家", "13800000000", "江苏省南京市测试路1号"));
    }

    private boolean buyAfterBarrier(CountDownLatch ready, CountDownLatch start,
                                    long buyerId, long itemId) throws Exception {
        ready.countDown();
        start.await();
        try {
            orders.buy(buyerId, itemId, UUID.randomUUID().toString(), inlineAddress());
            return true;
        } catch (SecondhandRuleException expected) {
            return false;
        }
    }

    private void createSchema() throws SQLException {
        try (Connection c = connections.openConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,display_name VARCHAR(100),enabled BOOLEAN)");
            s.execute("CREATE TABLE bank_accounts(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNIQUE,balance DECIMAL(15,2) DEFAULT 0,status VARCHAR(16) DEFAULT 'ACTIVE',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE bank_ledger_entries(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id BIGINT,entry_type VARCHAR(32),direction VARCHAR(8),amount DECIMAL(15,2),balance_after DECIMAL(15,2),reference_no VARCHAR(64),counterparty_user_id BIGINT,operator_user_id BIGINT,description VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(account_id,entry_type,reference_no))");
            s.execute("CREATE TABLE notifications(id BIGINT AUTO_INCREMENT PRIMARY KEY,recipient_user_id BIGINT,sender_user_id BIGINT,notification_type VARCHAR(40),source_module VARCHAR(40),title VARCHAR(160),content VARCHAR(1000),target VARCHAR(40),related_entity_id BIGINT,is_read BOOLEAN DEFAULT FALSE,read_at TIMESTAMP,deleted_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE shop_shipping_addresses(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,slot INT,version_token VARCHAR(64),recipient VARCHAR(40),phone VARCHAR(32),address_text VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,slot))");
            s.execute("CREATE TABLE secondhand_items(id BIGINT AUTO_INCREMENT PRIMARY KEY,seller_user_id BIGINT,title VARCHAR(120),description VARCHAR(1000),category VARCHAR(32),condition_text VARCHAR(80),price DECIMAL(15,2),item_status VARCHAR(16) DEFAULT 'AVAILABLE',moderation_status VARCHAR(16) DEFAULT 'VISIBLE',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE secondhand_orders(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(64) UNIQUE,item_id BIGINT,buyer_user_id BIGINT,seller_user_id BIGINT,purchase_operation_id VARCHAR(64) UNIQUE,amount DECIMAL(15,2),status VARCHAR(16) DEFAULT 'PAID',title_snapshot VARCHAR(120),category_snapshot VARCHAR(32),condition_snapshot VARCHAR(80),price_snapshot DECIMAL(15,2),seller_name_snapshot VARCHAR(100),shipping_recipient VARCHAR(40),shipping_phone VARCHAR(32),shipping_address VARCHAR(255),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,shipped_at TIMESTAMP,completed_at TIMESTAMP,cancelled_at TIMESTAMP)");
            s.execute("CREATE TABLE secondhand_escrows(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNIQUE,amount DECIMAL(15,2),status VARCHAR(16) DEFAULT 'HELD',created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE secondhand_order_resolution_logs(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,operator_user_id BIGINT,operation_id VARCHAR(64) UNIQUE,action VARCHAR(16),reason VARCHAR(500),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private void execute(String sql) throws SQLException { try (Connection c=connections.openConnection(); Statement s=c.createStatement()){s.executeUpdate(sql);} }
    private int scalar(String sql) throws SQLException { try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);} }
    private String scalarString(String sql) throws SQLException { try(Connection c=connections.openConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getString(1);} }
}
