package com.vcampus.common.model;

import java.util.Objects;

public record ShippingAddressValue(String recipient, String phone, String address) {
    public ShippingAddressValue {
        recipient = normalized(recipient, "收件人", 1, 40);
        phone = normalized(phone, "联系电话", 5, 32);
        address = normalized(address, "收货地址", 5, 255);
    }

    private static String normalized(String value, String label, int min, int max) {
        String normalized = Objects.requireNonNull(value, label + "不能为空").trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(label + "长度应为 " + min + "～" + max + " 个字符");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + "不能包含控制字符");
        }
        return normalized;
    }
}
