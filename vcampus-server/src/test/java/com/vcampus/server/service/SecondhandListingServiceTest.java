package com.vcampus.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.ConnectionFactory;
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
    private String seller;
    private String other;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:secondhand_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        createSchema(url);
        SessionManager sessions = new SessionManager();
        seller = token(sessions, 1, Set.of(UserRole.STUDENT));
        other = token(sessions, 2, Set.of(UserRole.STUDENT));
        admin = token(sessions, 9, Set.of(UserRole.TEACHER, UserRole.SHOP_ADMIN));
        service = new SecondhandListingService(new SecondhandListingRepository(
                new ConnectionFactory(new DatabaseConfig(url, "sa", ""))), sessions);
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
            statement.execute(table(sql, "secondhand_listing_moderation_logs"));
            statement.execute("INSERT INTO users VALUES(1,'卖家'),(2,'其他用户'),(9,'管理员')");
        }
    }

    private static String table(String sql, String name) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + name);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }
}
