package com.vcampus.server.model;

import com.vcampus.common.model.ShopInventoryMovementType;

import java.time.Instant;

public record ShopInventoryMovementRecord(long id, long productId,
                                          ShopInventoryMovementType movementType,
                                          int quantityDelta, int stockAfter, Long orderId,
                                          Long operatorUserId, String reason, Instant createdAt) {
}
