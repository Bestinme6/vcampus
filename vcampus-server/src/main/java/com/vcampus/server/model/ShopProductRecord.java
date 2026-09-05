package com.vcampus.server.model;

import com.vcampus.common.model.ShopCategory;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopProductRecord(long id, String sku, String name, String description,
                                ShopCategory category, BigDecimal price, int stock, boolean enabled,
                                Instant createdAt, Instant updatedAt) {
}
