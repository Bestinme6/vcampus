package com.vcampus.server.database;

import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import java.sql.SQLException;
import java.util.List;

public interface ShopAddressStore {
    List<ShopAddress> list(long userId) throws SQLException;

    ShopAddress save(long userId, Integer replaceSlot, ShippingAddressValue value) throws SQLException;

    boolean delete(long userId, int slot) throws SQLException;
}
