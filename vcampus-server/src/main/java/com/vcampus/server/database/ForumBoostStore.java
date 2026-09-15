package com.vcampus.server.database;

import com.vcampus.common.model.ForumBoostTier;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ForumBoostStore {
    record BoostState(
            boolean eligible,
            String reason,
            int activeBoostCount,
            int activeHeat,
            int userOperationsUsed,
            BigDecimal userAmountUsed,
            BigDecimal postAmountUsed,
            BigDecimal balance,
            List<ForumBoostTier> availableTiers) {
        public BoostState {
            availableTiers = List.copyOf(availableTiers);
        }
    }

    record BoostReceipt(
            long boostId,
            long bankLedgerId,
            String bankReference,
            BigDecimal amount,
            int initialHeat,
            BigDecimal balanceAfter,
            int activeBoostCount,
            int activeHeat,
            boolean duplicate) {
    }

    BoostReceipt create(
            long postId,
            long actorUserId,
            ForumBoostTier tier,
            UUID operationId,
            Instant dayStart,
            Instant dayEnd,
            Instant now) throws SQLException;

    BoostState state(
            long postId,
            long actorUserId,
            Instant dayStart,
            Instant dayEnd,
            Instant now) throws SQLException;
}
