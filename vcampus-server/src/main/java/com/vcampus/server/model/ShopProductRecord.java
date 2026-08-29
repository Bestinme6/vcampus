package com.vcampus.server.model;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopProductRecord(long id, String sku, String name, String description,
                                BigDecimal price, int stock, boolean enabled,
                                Instant createdAt, Instant updatedAt) {
}
