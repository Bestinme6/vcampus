package com.vcampus.server.database;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ShopAddressRepository implements ShopAddressStore {
    private final ConnectionFactory connections;

    public ShopAddressRepository(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public List<ShopAddress> list(long userId) throws SQLException {
        requireUserId(userId);
        List<ShopAddress> addresses = new ArrayList<>();
        try (var connection = connections.openConnection();
             var statement = connection.prepareStatement(
                     "SELECT slot,version_token,recipient,phone,address_text "
                             + "FROM shop_shipping_addresses WHERE user_id=? ORDER BY slot")) {
            statement.setLong(1, userId);
            try (var result = statement.executeQuery()) {
                while (result.next()) addresses.add(row(result));
            }
        }
        return List.copyOf(addresses);
    }

    @Override
    public ShopAddress save(long userId, Integer replaceSlot, ShippingAddressValue value)
            throws SQLException {
        requireUserId(userId);
        Objects.requireNonNull(value, "value");
        if (replaceSlot != null && (replaceSlot < 1 || replaceSlot > 3)) {
            throw new IllegalArgumentException("收货地址槽位无效");
        }
        return transaction(connection -> {
            lockUser(connection, userId);
            int slot = replaceSlot == null ? firstFreeSlot(connection, userId) : replaceSlot;
            String version = UUID.randomUUID().toString();
            if (replaceSlot == null) {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO shop_shipping_addresses"
                                + "(user_id,slot,version_token,recipient,phone,address_text) "
                                + "VALUES(?,?,?,?,?,?)")) {
                    bind(statement, userId, slot, version, value);
                    statement.executeUpdate();
                }
            } else {
                try (var statement = connection.prepareStatement(
                        "UPDATE shop_shipping_addresses SET version_token=?,recipient=?,phone=?,"
                                + "address_text=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND slot=?")) {
                    statement.setString(1, version);
                    statement.setString(2, value.recipient());
                    statement.setString(3, value.phone());
                    statement.setString(4, value.address());
                    statement.setLong(5, userId);
                    statement.setInt(6, slot);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("请选择要覆盖的现有收货地址");
                    }
                }
            }
            return new ShopAddress(slot, version, value);
        });
    }

    @Override
    public boolean delete(long userId, int slot) throws SQLException {
        requireUserId(userId);
        if (slot < 1 || slot > 3) throw new IllegalArgumentException("收货地址槽位无效");
        return transaction(connection -> {
            lockUser(connection, userId);
            try (var statement = connection.prepareStatement(
                    "DELETE FROM shop_shipping_addresses WHERE user_id=? AND slot=?")) {
                statement.setLong(1, userId);
                statement.setInt(2, slot);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public ShippingAddressValue resolve(Connection connection, long userId,
                                        ShippingAddressChoice choice) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        requireUserId(userId);
        Objects.requireNonNull(choice, "choice");
        if (choice.inline() != null) return choice.inline();
        try (var statement = connection.prepareStatement(
                "SELECT recipient,phone,address_text,version_token FROM shop_shipping_addresses "
                        + "WHERE user_id=? AND slot=? FOR UPDATE")) {
            statement.setLong(1, userId);
            statement.setInt(2, choice.slot());
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getString(4).equals(choice.version())) {
                    throw new IllegalArgumentException("收货地址已变化，请重新确认");
                }
                return new ShippingAddressValue(result.getString(1), result.getString(2),
                        result.getString(3));
            }
        }
    }

    private int firstFreeSlot(Connection connection, long userId) throws SQLException {
        boolean[] used = new boolean[4];
        try (var statement = connection.prepareStatement(
                "SELECT slot FROM shop_shipping_addresses WHERE user_id=? ORDER BY slot")) {
            statement.setLong(1, userId);
            try (var result = statement.executeQuery()) {
                while (result.next()) used[result.getInt(1)] = true;
            }
        }
        for (int slot = 1; slot <= 3; slot++) if (!used[slot]) return slot;
        throw new IllegalArgumentException("最多保存三个收货地址，请选择一条覆盖");
    }

    private void lockUser(Connection connection, long userId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
            statement.setLong(1, userId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("用户不存在");
            }
        }
    }

    private static void bind(java.sql.PreparedStatement statement, long userId, int slot,
                             String version, ShippingAddressValue value) throws SQLException {
        statement.setLong(1, userId);
        statement.setInt(2, slot);
        statement.setString(3, version);
        statement.setString(4, value.recipient());
        statement.setString(5, value.phone());
        statement.setString(6, value.address());
    }

    private static ShopAddress row(java.sql.ResultSet result) throws SQLException {
        return new ShopAddress(result.getInt(1), result.getString(2),
                new ShippingAddressValue(result.getString(3), result.getString(4),
                        result.getString(5)));
    }

    private static void requireUserId(long userId) {
        if (userId < 1) throw new IllegalArgumentException("用户编号无效");
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (var connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
