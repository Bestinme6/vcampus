package com.vcampus.common.model;

import java.util.Objects;

public record ShopAddress(int slot, String version, ShippingAddressValue value) {
    public ShopAddress {
        if (slot < 1 || slot > 3) throw new IllegalArgumentException("收货地址槽位无效");
        version = Objects.requireNonNull(version, "地址版本不能为空").trim();
        if (version.isEmpty()) throw new IllegalArgumentException("地址版本不能为空");
        if (value == null) throw new IllegalArgumentException("收货地址不能为空");
    }
}
