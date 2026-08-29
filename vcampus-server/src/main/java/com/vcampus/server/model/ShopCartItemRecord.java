package com.vcampus.server.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopCartItemRecord(long productId, String sku, String name, String description,
                                 BigDecimal unitPrice, int quantity, int stock, boolean enabled,
                                 BigDecimal subtotal, Instant updatedAt) {
}
