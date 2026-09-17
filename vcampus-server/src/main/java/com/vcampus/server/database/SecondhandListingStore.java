package com.vcampus.server.database;

import com.vcampus.common.model.MoneyPolicy;
import com.vcampus.common.model.SecondhandCategory;
import com.vcampus.common.model.SecondhandItemStatus;
import com.vcampus.common.model.SecondhandModerationStatus;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface SecondhandListingStore {
    long create(long sellerId, ListingInput input) throws SQLException;

    Listing update(long actorId, long itemId, ListingInput input) throws SQLException;

    Listing withdraw(long actorId, long itemId) throws SQLException;

    Listing moderate(long adminId, long itemId, boolean visible, String reason) throws SQLException;

    ListingPage search(String keyword, int page, int pageSize) throws SQLException;

    default ListingPage search(String keyword, SecondhandCategory category, int page, int pageSize)
            throws SQLException {
        return search(keyword, page, pageSize);
    }

    default ListingPage searchAdmin(String keyword, int page, int pageSize) throws SQLException {
        throw new SQLException("Admin listing search is unavailable");
    }

    Listing get(long itemId, long viewerId, boolean admin) throws SQLException;

    record ListingInput(String title, String description, String category,
                        String condition, BigDecimal price) {
        public ListingInput {
            title = text(title, 2, 120, "物品标题无效");
            description = text(description, 2, 1000, "物品描述无效");
            try {
                category = SecondhandCategory.parse(category).name();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("物品分类无效", invalid);
            }
            condition = text(condition, 1, 80, "物品成色无效");
            try {
                price = MoneyPolicy.parsePositive(MoneyPolicy.format(price));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("物品价格无效", invalid);
            }
        }

        private static String text(String value, int minimum, int maximum, String message) {
            String normalized = Objects.requireNonNullElse(value, "").trim();
            if (normalized.length() < minimum || normalized.length() > maximum
                    || normalized.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(message);
            }
            return normalized;
        }
    }

    record Listing(long id, long sellerId, String sellerName, String title,
                   String description, String category, String condition,
                   BigDecimal price, SecondhandItemStatus status,
                   SecondhandModerationStatus moderation, Instant createdAt,
                   Instant updatedAt) {
        public Listing {
            sellerName = Objects.requireNonNull(sellerName, "sellerName");
            title = Objects.requireNonNull(title, "title");
            description = Objects.requireNonNull(description, "description");
            category = Objects.requireNonNull(category, "category");
            condition = Objects.requireNonNull(condition, "condition");
            price = Objects.requireNonNull(price, "price");
            status = Objects.requireNonNull(status, "status");
            moderation = Objects.requireNonNull(moderation, "moderation");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record ListingPage(List<Listing> rows, int page, int pageSize, int total) {
        public ListingPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        }
    }
}
