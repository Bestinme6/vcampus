package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.SecondhandListingStore.ListingInput;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecondhandListingRepositoryTest {
    private String url;
    private SecondhandListingRepository listings;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:secondhand_listing_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        createSchema();
        listings = new SecondhandListingRepository(
                new ConnectionFactory(new DatabaseConfig(url, "sa", "")));
    }

    @Test
    void sellerCanCreateUpdateAndWithdrawOwnAvailableItem() throws Exception {
        long id = listings.create(1L, input("旧教材", "12.00"));

        var created = listings.get(id, 1L, false);
        assertEquals(1L, created.sellerId());
        assertEquals("卖家甲", created.sellerName());
        assertEquals(SecondhandItemStatus.AVAILABLE, created.status());
        assertEquals(SecondhandModerationStatus.VISIBLE, created.moderation());

        listings.update(1L, id, input("旧教材（含笔记）", "10.00"));
        assertEquals("旧教材（含笔记）", listings.get(id, 1L, false).title());
        listings.withdraw(1L, id);
        assertEquals(SecondhandItemStatus.WITHDRAWN,
                listings.get(id, 1L, false).status());
        assertTrue(listings.search("旧教材", 1, 10).rows().isEmpty());
    }

    @Test
    void anotherUserCannotEditOrWithdrawTheListing() throws Exception {
        long id = listings.create(1L, input("旧教材", "12.00"));

        SecondhandRuleException update = assertThrows(SecondhandRuleException.class,
                () -> listings.update(2L, id, input("改名", "12.00")));
        assertEquals("无权修改该二手物品", update.getMessage());
        assertThrows(SecondhandRuleException.class, () -> listings.withdraw(2L, id));
    }

    @Test
    void administratorModerationIsSeparateAndHiddenItemCannotBeRepublishedBySeller()
            throws Exception {
        long id = listings.create(1L, input("旧教材", "12.00"));
        listings.moderate(9L, id, false, "违规商品信息");

        assertTrue(listings.search("旧教材", 1, 10).rows().isEmpty());
        assertEquals(SecondhandModerationStatus.HIDDEN,
                listings.get(id, 1L, false).moderation());
        assertThrows(SecondhandRuleException.class,
                () -> listings.update(1L, id, input("试图重新发布", "12.00")));
        assertEquals(SecondhandModerationStatus.HIDDEN,
                listings.get(id, 9L, true).moderation());

        listings.moderate(9L, id, true, "复核后恢复");
        assertEquals(1, listings.search("旧教材", 1, 10).total());
        assertEquals(2, scalar("SELECT COUNT(*) FROM secondhand_listing_moderation_logs"));
    }

    @Test
    void inputsAndPagingRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ListingInput("", "说明文字", "BOOK", "九成新",
                        new BigDecimal("12.00")));
        assertThrows(IllegalArgumentException.class,
                () -> input("旧教材", "0.00"));
        assertThrows(IllegalArgumentException.class,
                () -> listings.search("", 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> listings.search("", 1, 101));
    }

    @Test
    void adminSearchPagesAcrossHiddenAndWithdrawnItems() throws Exception {
        long hidden = listings.create(1L,input("管理教材","12.00"));
        long withdrawn = listings.create(1L,input("管理耳机","25.00"));
        listings.moderate(9L,hidden,false,"违规信息");
        listings.withdraw(1L,withdrawn);
        var first = listings.searchAdmin("管理",1,1);
        var second = listings.searchAdmin("管理",2,1);
        assertEquals(2,first.total());
        assertEquals(1,first.rows().size());
        assertEquals(1,second.rows().size());
        assertTrue(first.rows().getFirst().id()!=second.rows().getFirst().id());
        assertEquals(2,listings.searchAdmin("卖家甲",1,12).total());
        assertEquals(0,listings.search("管理",1,12).total());
        assertEquals(0,listings.searchAdmin("不存在",1,12).total());
        assertThrows(IllegalArgumentException.class,()->listings.searchAdmin("",0,12));
    }

    private static ListingInput input(String title, String price) {
        return new ListingInput(title, "九成新，含少量笔记", "BOOK", "九成新",
                new BigDecimal(price));
    }

    private void createSchema() throws Exception {
        String migration = Files.readString(Path.of("..", "database", "migrations",
                "026_secondhand_market.sql"));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,"
                    + "display_name VARCHAR(100) NOT NULL)");
            statement.execute(extractCreateTable(migration, "secondhand_items"));
            statement.execute(extractCreateTable(migration,
                    "secondhand_listing_moderation_logs"));
            statement.execute("INSERT INTO users VALUES(1,'卖家甲'),(2,'其他用户'),(9,'管理员')");
        }
    }

    private int scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String extractCreateTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end + 1);
    }
}
