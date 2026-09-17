package com.vcampus.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.SecondhandImageRepository;
import com.vcampus.server.database.SecondhandListingRepository;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecondhandListingServiceTest {
    private SecondhandListingService service;
    private String url;
    private String seller;
    private String other;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:secondhand_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        createSchema(url);
        SessionManager sessions = new SessionManager();
        seller = token(sessions, 1, Set.of(UserRole.STUDENT));
        other = token(sessions, 2, Set.of(UserRole.STUDENT));
        admin = token(sessions, 9, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        ConnectionFactory connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        service = new SecondhandListingService(new SecondhandListingRepository(connections),
                new SecondhandImageRepository(connections), sessions);
    }

    @Test
    void sessionIdentityOwnsCreateUpdateAndWithdraw() {
        var created = service.create(request(Actions.SHOP_USED_ITEM_CREATE, seller, item("旧教材")));
        assertTrue(created.success());
        long itemId = Long.parseLong(created.data().get("itemId"));

        assertFalse(service.update(request(Actions.SHOP_USED_ITEM_UPDATE, other,
                with(item("恶意改名"), "itemId", Long.toString(itemId)))).success());
        assertTrue(service.update(request(Actions.SHOP_USED_ITEM_UPDATE, seller,
                with(item("旧教材（含笔记）"), "itemId", Long.toString(itemId)))).success());
        assertTrue(service.withdraw(request(Actions.SHOP_USED_ITEM_WITHDRAW, seller,
                Map.of("itemId", Long.toString(itemId)))).success());
        assertEquals("0", service.search(request(Actions.SHOP_USED_ITEM_SEARCH, other,
                Map.of("keyword", "旧教材", "page", "1"))).data().get("count"));
    }

    @Test
    void onlyShopAdministratorCanHideAndRestoreWithReason() {
        long itemId = Long.parseLong(service.create(request(Actions.SHOP_USED_ITEM_CREATE,
                seller, item("旧教材"))).data().get("itemId"));

        assertFalse(service.hide(request(Actions.SHOP_USED_ADMIN_ITEM_HIDE, other,
                Map.of("itemId", Long.toString(itemId), "reason", "违规信息"))).success());
        assertTrue(service.hide(request(Actions.SHOP_USED_ADMIN_ITEM_HIDE, admin,
                Map.of("itemId", Long.toString(itemId), "reason", "违规信息"))).success());
        assertEquals("0", service.search(request(Actions.SHOP_USED_ITEM_SEARCH, other,
                Map.of("keyword", "", "page", "1"))).data().get("count"));
        assertFalse(service.update(request(Actions.SHOP_USED_ITEM_UPDATE, seller,
                with(item("自行恢复"), "itemId", Long.toString(itemId)))).success());
        assertTrue(service.restore(request(Actions.SHOP_USED_ADMIN_ITEM_RESTORE, admin,
                Map.of("itemId", Long.toString(itemId), "reason", "复核通过"))).success());
    }

    @Test
    void validatesAndFiltersStableSecondhandCategories() {
        assertTrue(service.create(request(Actions.SHOP_USED_ITEM_CREATE, seller,
                item("旧教材"))).success());
        assertTrue(service.create(request(Actions.SHOP_USED_ITEM_CREATE, seller,
                with(item("旧耳机"), "category", "DIGITAL"))).success());
        assertFalse(service.create(request(Actions.SHOP_USED_ITEM_CREATE, seller,
                with(item("未知分类"), "category", "FREE_TEXT"))).success());

        var books = service.search(request(Actions.SHOP_USED_ITEM_SEARCH, other,
                Map.of("keyword", "", "category", "BOOK", "page", "1")));
        assertTrue(books.success());
        assertEquals("1", books.data().get("count"));
    }

    @Test
    void searchAndDetailIncludeOrderedImageMetadata() throws Exception {
        long itemId = Long.parseLong(service.create(request(Actions.SHOP_USED_ITEM_CREATE,
                seller, item("带图旧教材"))).data().get("itemId"));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "INSERT INTO secondhand_item_images(item_id,storage_key,"
                             + "thumbnail_storage_key,mime_type,byte_size,sha256,sort_order,is_cover)"
                             + " VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, itemId);
            statement.setString(2, "detail/key");
            statement.setString(3, "thumb/key");
            statement.setString(4, "image/png");
            statement.setLong(5, 128);
            statement.setString(6, "a".repeat(64));
            statement.setInt(7, 0);
            statement.setBoolean(8, true);
            statement.executeUpdate();
        }

        var page = service.search(request(Actions.SHOP_USED_ITEM_SEARCH, other,
                Map.of("keyword", "带图", "page", "1")));
        assertEquals("1", page.data().get("row.0.imageCount"));
        assertTrue(page.data().containsKey("row.0.image.0"));

        var detail = service.get(request(Actions.SHOP_USED_ITEM_GET, other,
                Map.of("itemId", Long.toString(itemId))));
        assertEquals("1", detail.data().get("imageCount"));
        assertTrue(detail.data().containsKey("image.0"));
    }

    private static Map<String, String> item(String title) {
        return Map.of("title", title, "description", "九成新，含少量笔记",
                "category", "BOOK", "condition", "九成新", "price", "12.00");
    }

    private static Map<String, String> with(Map<String, String> source, String key, String value) {
        Map<String, String> result = new LinkedHashMap<>(source);
        result.put(key, value);
        return result;
    }

    private static RequestMessage request(String action, String token, Map<String, String> values) {
        Map<String, String> parameters = new LinkedHashMap<>(values);
        parameters.put("sessionToken", token);
        return RequestMessage.create(action, parameters);
    }

    private static String token(SessionManager sessions, long id, Set<UserRole> roles) {
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
}
