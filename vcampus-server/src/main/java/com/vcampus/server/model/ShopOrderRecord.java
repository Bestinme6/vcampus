package com.vcampus.server.model;

import com.vcampus.common.model.ShopOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ShopOrderRecord(long id, String orderNo, long buyerUserId, String buyerUsername,
                              String buyerDisplayName, String checkoutOperationId,
                              BigDecimal totalAmount, ShopOrderStatus status, Instant createdAt,
                              Instant shippedAt, Instant completedAt, Instant cancelledAt) {
}
