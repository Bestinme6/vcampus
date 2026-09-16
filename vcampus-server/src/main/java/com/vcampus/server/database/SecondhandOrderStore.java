package com.vcampus.server.database;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopOrderStatus;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface SecondhandOrderStore {
    BuyResult buy(long buyerId, long itemId, String operationId, ShippingAddressChoice address)
            throws SQLException;

    OrderDetail order(long requesterId, long orderId, boolean admin) throws SQLException;

    OrderPage search(long requesterId, boolean selling, int page, int pageSize) throws SQLException;

    OrderPage searchAdmin(String keyword, ShopOrderStatus status, int page, int pageSize)
            throws SQLException;

    OrderDetail cancel(long buyerId, long orderId) throws SQLException;

    OrderDetail ship(long sellerId, long orderId) throws SQLException;

    OrderDetail confirm(long buyerId, long orderId) throws SQLException;

    OrderDetail resolve(long adminId, long orderId, Resolution resolution,
                        String operationId, String reason) throws SQLException;

    enum Resolution { REFUND, SETTLE }

    enum EscrowStatus { HELD, REFUNDED, SETTLED }

    record BuyResult(long orderId, String orderNo, ShopOrderStatus status, boolean duplicate) {
        public BuyResult {
            Objects.requireNonNull(orderNo, "orderNo");
            Objects.requireNonNull(status, "status");
        }
    }

    record OrderDetail(long id, String orderNo, long itemId, long buyerId, long sellerId,
                       BigDecimal amount, ShopOrderStatus status, EscrowStatus escrowStatus,
                       String title, String category, String condition, String sellerName,
                       ShippingAddressValue shippingAddress, Instant createdAt, Instant shippedAt,
                       Instant completedAt, Instant cancelledAt) {
        public OrderDetail {
            Objects.requireNonNull(orderNo, "orderNo");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(escrowStatus, "escrowStatus");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(sellerName, "sellerName");
            Objects.requireNonNull(shippingAddress, "shippingAddress");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    record OrderPage(List<OrderDetail> rows, int page, int pageSize, int total) {
        public OrderPage { rows = List.copyOf(Objects.requireNonNull(rows, "rows")); }
    }
}
