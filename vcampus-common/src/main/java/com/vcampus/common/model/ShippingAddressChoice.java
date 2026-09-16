package com.vcampus.common.model;

public record ShippingAddressChoice(Integer slot, String version, ShippingAddressValue inline) {
    public ShippingAddressChoice {
        boolean saved = slot != null || version != null;
        if (saved == (inline != null)) {
            throw new IllegalArgumentException("请选择已保存地址或填写一次性地址");
        }
        if (saved) {
            if (slot == null || slot < 1 || slot > 3 || version == null || version.isBlank()) {
                throw new IllegalArgumentException("已保存地址信息无效");
            }
            version = version.trim();
        }
    }
}
