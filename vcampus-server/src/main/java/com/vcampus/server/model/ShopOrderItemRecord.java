package com.vcampus.server.model;

import java.math.BigDecimal;

public record ShopOrderItemRecord(long id, long orderId, long productId, String skuSnapshot,
                                  String nameSnapshot, BigDecimal unitPrice, int quantity,
                                  BigDecimal subtotal) {
}
