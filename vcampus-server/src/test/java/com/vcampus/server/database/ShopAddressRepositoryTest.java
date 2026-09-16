package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import com.vcampus.server.config.DatabaseConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopAddressRepositoryTest {
    private String url;
    private ConnectionFactory connections;
    private ShopAddressRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:shop_address_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        createSchema();
        repository = new ShopAddressRepository(connections);
    }

    @Test
    void valueTrimsFieldsAndRejectsLengthsAndControlCharacters() {
        assertEquals(new ShippingAddressValue("小林", "13800138000", "江苏省南京市文昌路 1 号"),
                new ShippingAddressValue("  小林  ", " 13800138000 ", " 江苏省南京市文昌路 1 号 "));

        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("", "13800138000", "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("甲".repeat(41), "13800138000", "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "1234", "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "1".repeat(33), "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "13800138000", "四字"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "13800138000", "甲".repeat(256)));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小\n林", "13800138000", "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "138\t00138000", "江苏省南京市文昌路 1 号"));
        assertThrows(IllegalArgumentException.class,
                () -> new ShippingAddressValue("小林", "13800138000", "江苏省南京市\r文昌路 1 号"));
    }

    @Test
    void choicesAndResponsesAcceptOnlyValidSavedOrInlineModes() {
        ShippingAddressValue value = address("甲");
        assertEquals(2, new ShippingAddressChoice(2, "v1", null).slot());
        assertEquals(value, new ShippingAddressChoice(null, null, value).inline());
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(1, "v1", value));
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(0, "v1", null));
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(4, "v1", null));
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(1, " ", null));
        assertThrows(IllegalArgumentException.class, () -> new ShippingAddressChoice(null, "v1", value));

        assertEquals(value, new ShopAddress(3, "version", value).value());
        assertThrows(IllegalArgumentException.class, () -> new ShopAddress(0, "version", value));
        assertThrows(IllegalArgumentException.class, () -> new ShopAddress(1, " ", value));
        assertThrows(IllegalArgumentException.class, () -> new ShopAddress(1, "version", null));
    }

    @Test
    void savesFirstThreeSlotsRejectsFourthAndReplacesExplicitSlotWithNewVersion() throws Exception {
        ShopAddress first = repository.save(7L, null, address("甲"));
        ShopAddress second = repository.save(7L, null, address("乙"));
        ShopAddress third = repository.save(7L, null, address("丙"));

        assertEquals(List.of(1, 2, 3), repository.list(7L).stream().map(ShopAddress::slot).toList());
        assertEquals(List.of(first, second, third), repository.list(7L));
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(7L, null, address("丁")));

        ShopAddress replaced = repository.save(7L, 2, address("新"));
        assertEquals(2, replaced.slot());
        assertEquals(address("新"), replaced.value());
        assertNotEquals(second.version(), replaced.version());
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM shop_shipping_addresses WHERE user_id=7"));
    }

    @Test
    void resolvesCurrentSavedChoiceAndRejectsStaleVersion() throws Exception {
        ShopAddress saved = repository.save(7L, null, address("甲"));
        try (Connection connection = connections.openConnection()) {
            assertEquals(saved.value(), repository.resolve(connection, 7L,
                    new ShippingAddressChoice(saved.slot(), saved.version(), null)));
        }

        repository.save(7L, saved.slot(), address("新"));
        try (Connection connection = connections.openConnection()) {
            IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                    () -> repository.resolve(connection, 7L,
                            new ShippingAddressChoice(saved.slot(), saved.version(), null)));
            assertEquals("收货地址已变化，请重新确认", stale.getMessage());
        }
    }

    @Test
    void resolvesInlineChoiceWithoutPersistingIt() throws Exception {
        ShippingAddressValue inline = address("临");
        try (Connection connection = connections.openConnection()) {
            assertEquals(inline, repository.resolve(connection, 7L,
                    new ShippingAddressChoice(null, null, inline)));
        }
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM shop_shipping_addresses"));
    }

    @Test
    void replaceAndDeleteAffectOnlyTheAuthenticatedOwner() throws Exception {
        ShopAddress ownerSeven = repository.save(7L, null, address("甲"));
        ShopAddress ownerEight = repository.save(8L, null, address("乙"));

        ShopAddress replacedEight = repository.save(8L, 1, address("新"));
        assertEquals(ownerSeven, repository.list(7L).getFirst());
        assertNotEquals(ownerEight.version(), replacedEight.version());
        assertTrue(repository.delete(8L, 1));
        assertFalse(repository.delete(8L, 1));
        assertEquals(List.of(ownerSeven), repository.list(7L));
        assertTrue(repository.list(8L).isEmpty());
    }

    @Test
    void concurrentFourthSaveNeverCreatesMoreThanThreeRows() throws Exception {
        repository.save(7L, null, address("甲"));
        repository.save(7L, null, address("乙"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ShopAddress> first = executor.submit(() -> {
                start.await();
                return repository.save(7L, null, address("丙"));
            });
            Future<ShopAddress> second = executor.submit(() -> {
                start.await();
                return repository.save(7L, null, address("丁"));
            });
            start.countDown();

            int successes = successCount(first) + successCount(second);
            assertEquals(1, successes);
        }
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM shop_shipping_addresses WHERE user_id=7"));
    }

    private int successCount(Future<ShopAddress> future) throws Exception {
        try {
            future.get();
            return 1;
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof IllegalArgumentException,
                    () -> "Unexpected failure: " + exception.getCause());
            return 0;
        }
    }

    private ShippingAddressValue address(String marker) {
        return new ShippingAddressValue(marker + "同学", "13800138000", "江苏省南京市文昌路 " + marker + " 号");
    }

    private void createSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100) NOT NULL)");
            String migration = Files.readString(Path.of("..", "database", "migrations",
                    "025_shop_shipping_addresses.sql"));
            statement.execute(extractCreateTable(migration, "shop_shipping_addresses"));
            statement.execute("INSERT INTO users VALUES(7,'小林'),(8,'小周')");
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String extractCreateTable(String sql, String table) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end + 1);
    }
}
