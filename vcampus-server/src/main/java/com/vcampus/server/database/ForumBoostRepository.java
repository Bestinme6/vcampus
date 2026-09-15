package com.vcampus.server.database;

import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ForumBoostRepository implements ForumBoostStore {
    private static final BigDecimal USER_DAILY_LIMIT = new BigDecimal("30.00");
    private static final BigDecimal POST_DAILY_LIMIT = new BigDecimal("200.00");
    private static final int USER_OPERATION_LIMIT = 3;
    private final ConnectionFactory connections;
    private final BankPaymentPort bank;
    private final NotificationWriter notifications;

    public ForumBoostRepository(
            ConnectionFactory connections,
            BankPaymentPort bank,
            NotificationWriter notifications) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public BoostReceipt create(
            long postId,
            long actorUserId,
            ForumBoostTier tier,
            UUID operationId,
            Instant dayStart,
            Instant dayEnd,
            Instant now) throws SQLException {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(dayStart, "dayStart");
        Objects.requireNonNull(dayEnd, "dayEnd");
        Objects.requireNonNull(now, "now");
        String reference = "FB-" + operationId;
        try (Connection connection = connections.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ExistingBoost existing = existingBoost(connection, actorUserId, operationId);
                if (existing != null) {
                    if (existing.postId() != postId || existing.tier() != tier) {
                        throw new ForumBoostRuleException("操作号已用于其他助力请求");
                    }
                    Aggregate aggregate = aggregate(connection, postId, now);
                    connection.commit();
                    return new BoostReceipt(
                            existing.boostId(), existing.bankLedgerId(), existing.reference(),
                            existing.amount(), existing.initialHeat(), existing.balanceAfter(),
                            aggregate.count(), aggregate.heat(), true);
                }
                PostState post = lockPost(connection, postId);
                String postReason = ineligiblePostReason(post, actorUserId);
                if (postReason != null) {
                    throw new ForumBoostRuleException(postReason);
                }
                Usage usage = usage(connection, postId, actorUserId, dayStart, dayEnd);
                validateLimits(usage, tier);
                var payment = bank.debitForForum(
                        connection, actorUserId, tier.amount(), reference,
                        "论坛助力推荐：帖子 #" + postId);
                if (payment.duplicate()) {
                    ExistingBoost raced = existingBoost(connection, actorUserId, operationId);
                    if (raced == null) {
                        throw new SQLException("论坛助力幂等记录不完整");
                    }
                    if (raced.postId() != postId || raced.tier() != tier) {
                        throw new ForumBoostRuleException("操作号已用于其他助力请求");
                    }
                    Aggregate aggregate = aggregate(connection, postId, now);
                    connection.commit();
                    return new BoostReceipt(
                            raced.boostId(), raced.bankLedgerId(), raced.reference(),
                            raced.amount(), raced.initialHeat(), raced.balanceAfter(),
                            aggregate.count(), aggregate.heat(), true);
                }
                long boostId = insertBoost(
                        connection, postId, actorUserId, tier, operationId,
                        payment.ledgerId(), now, now.plus(48, ChronoUnit.HOURS));
                notifications.insert(connection, new NotificationDraft(
                        actorUserId,
                        null,
                        NotificationType.BANK_FORUM_BOOST_PAID,
                        NotificationSource.BANK,
                        "论坛助力扣费成功",
                        "你为帖子《" + post.title() + "》支付 " + tier.amount().toPlainString()
                                + " 元，增加 " + tier.initialHeat() + " 初始热度。账户余额 "
                                + payment.balanceAfter().toPlainString() + " 元，流水号 " + reference + "。",
                        NotificationTarget.BANK_LEDGER,
                        payment.accountId()));
                Aggregate aggregate = aggregate(connection, postId, now);
                connection.commit();
                return new BoostReceipt(
                        boostId, payment.ledgerId(), reference, tier.amount(), tier.initialHeat(),
                        payment.balanceAfter(), aggregate.count(), aggregate.heat(), false);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public BoostState state(
            long postId,
            long actorUserId,
            Instant dayStart,
            Instant dayEnd,
            Instant now) throws SQLException {
        Objects.requireNonNull(dayStart, "dayStart");
        Objects.requireNonNull(dayEnd, "dayEnd");
        Objects.requireNonNull(now, "now");
        try (Connection connection = connections.openConnection()) {
            PostState post = findPost(connection, postId);
            Aggregate aggregate = aggregate(connection, postId, now);
            Usage usage = usage(connection, postId, actorUserId, dayStart, dayEnd);
            var account = bank.paymentAccount(actorUserId);
            String reason = ineligiblePostReason(post, actorUserId);
            List<ForumBoostTier> tiers = reason == null
                    ? Arrays.stream(ForumBoostTier.values())
                    .filter(tier -> allowed(usage, tier))
                    .filter(tier -> account.status()
                            == com.vcampus.common.model.BankAccountStatus.ACTIVE)
                    .filter(tier -> account.balance().compareTo(tier.amount()) >= 0)
                    .toList()
                    : List.of();
            if (reason == null && usage.userOperations() >= USER_OPERATION_LIMIT) {
                reason = "今日对该帖的助力次数已达上限";
            } else if (reason == null && usage.userAmount().compareTo(USER_DAILY_LIMIT) >= 0) {
                reason = "今日对该帖的助力金额已达上限";
            } else if (reason == null && usage.postAmount().compareTo(POST_DAILY_LIMIT) >= 0) {
                reason = "该帖今日助力金额已达上限";
            } else if (reason == null && account.status()
                    == com.vcampus.common.model.BankAccountStatus.FROZEN) {
                reason = "银行账户已冻结";
            } else if (reason == null && tiers.isEmpty()) {
                reason = "余额不足或剩余额度不足";
            }
            return new BoostState(
                    !tiers.isEmpty(), reason == null ? "" : reason,
                    aggregate.count(), aggregate.heat(), usage.userOperations(),
                    usage.userAmount(), usage.postAmount(), account.balance(), tiers);
        }
    }

    private ExistingBoost existingBoost(
            Connection connection, long actorUserId, UUID operationId) throws SQLException {
        String sql = "SELECT b.id,b.post_id,b.tier,b.amount,b.initial_heat,b.bank_ledger_id," +
                "l.reference_no,l.balance_after FROM forum_post_boosts b " +
                "JOIN bank_ledger_entries l ON l.id=b.bank_ledger_id " +
                "WHERE b.sponsor_user_id=? AND b.operation_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, actorUserId);
            statement.setString(2, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new ExistingBoost(
                        result.getLong("id"), result.getLong("post_id"),
                        ForumBoostTier.valueOf(result.getString("tier")),
                        result.getBigDecimal("amount").setScale(2), result.getInt("initial_heat"),
                        result.getLong("bank_ledger_id"), result.getString("reference_no"),
                        result.getBigDecimal("balance_after").setScale(2)) : null;
            }
        }
    }

    private PostState lockPost(Connection connection, long postId) throws SQLException {
        String sql = "SELECT p.author_user_id,p.title,p.status,p.pinned,p.is_announcement," +
                "s.enabled FROM forum_posts p JOIN forum_sections s ON s.id=p.section_id " +
                "WHERE p.id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, postId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PostState(
                        result.getLong("author_user_id"), result.getString("title"),
                        result.getString("status"), result.getBoolean("pinned"),
                        result.getBoolean("is_announcement"), result.getBoolean("enabled")) : null;
            }
        }
    }

    private PostState findPost(Connection connection, long postId) throws SQLException {
        String sql = "SELECT p.author_user_id,p.title,p.status,p.pinned,p.is_announcement," +
                "s.enabled FROM forum_posts p JOIN forum_sections s ON s.id=p.section_id " +
                "WHERE p.id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, postId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PostState(
                        result.getLong("author_user_id"), result.getString("title"),
                        result.getString("status"), result.getBoolean("pinned"),
                        result.getBoolean("is_announcement"), result.getBoolean("enabled")) : null;
            }
        }
    }

    private String ineligiblePostReason(PostState post, long actorUserId) {
        if (post == null) return "帖子不存在";
        if (post.authorUserId() == actorUserId) return "不能助力自己的帖子";
        if (!post.enabled()) return "帖子所属板块已停用";
        if (!"NORMAL".equals(post.status())) return "帖子当前不可见";
        if (post.pinned()) return "管理员置顶帖不能助力";
        if (post.announcement()) return "公告帖不能助力";
        return null;
    }

    private Usage usage(
            Connection connection,
            long postId,
            long actorUserId,
            Instant dayStart,
            Instant dayEnd) throws SQLException {
        String amountsSql = "SELECT " +
                "COALESCE(SUM(CASE WHEN sponsor_user_id=? THEN amount ELSE 0 END),0) AS user_amount," +
                "COALESCE(SUM(amount),0) AS post_amount " +
                "FROM forum_post_boosts WHERE post_id=? AND created_at>=? AND created_at<?";
        BigDecimal userAmount;
        BigDecimal postAmount;
        try (PreparedStatement statement = connection.prepareStatement(amountsSql)) {
            statement.setLong(1, actorUserId);
            statement.setLong(2, postId);
            statement.setTimestamp(3, Timestamp.from(dayStart));
            statement.setTimestamp(4, Timestamp.from(dayEnd));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                userAmount = result.getBigDecimal("user_amount").setScale(2);
                postAmount = result.getBigDecimal("post_amount").setScale(2);
            }
        }
        int userOperations;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM forum_post_boosts WHERE post_id=? " +
                        "AND sponsor_user_id=? AND created_at>=? AND created_at<?")) {
            statement.setLong(1, postId);
            statement.setLong(2, actorUserId);
            statement.setTimestamp(3, Timestamp.from(dayStart));
            statement.setTimestamp(4, Timestamp.from(dayEnd));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                userOperations = result.getInt(1);
            }
        }
        return new Usage(userOperations, userAmount, postAmount);
    }

    private boolean allowed(Usage usage, ForumBoostTier tier) {
        return usage.userOperations() < USER_OPERATION_LIMIT
                && usage.userAmount().add(tier.amount()).compareTo(USER_DAILY_LIMIT) <= 0
                && usage.postAmount().add(tier.amount()).compareTo(POST_DAILY_LIMIT) <= 0;
    }

    private void validateLimits(Usage usage, ForumBoostTier tier)
            throws ForumBoostRuleException {
        if (usage.userOperations() >= USER_OPERATION_LIMIT) {
            throw new ForumBoostRuleException("今日对该帖最多助力 3 次");
        }
        if (usage.userAmount().add(tier.amount()).compareTo(USER_DAILY_LIMIT) > 0) {
            throw new ForumBoostRuleException("今日对该帖最多助力 30.00 元");
        }
        if (usage.postAmount().add(tier.amount()).compareTo(POST_DAILY_LIMIT) > 0) {
            throw new ForumBoostRuleException("该帖子今日助力金额最多 200.00 元");
        }
    }

    private long insertBoost(
            Connection connection,
            long postId,
            long actorUserId,
            ForumBoostTier tier,
            UUID operationId,
            long ledgerId,
            Instant createdAt,
            Instant expiresAt) throws SQLException {
        String sql = "INSERT INTO forum_post_boosts(post_id,sponsor_user_id,tier,amount," +
                "initial_heat,operation_id,bank_ledger_id,created_at,expires_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, postId);
            statement.setLong(2, actorUserId);
            statement.setString(3, tier.name());
            statement.setBigDecimal(4, tier.amount());
            statement.setInt(5, tier.initialHeat());
            statement.setString(6, operationId.toString());
            statement.setLong(7, ledgerId);
            statement.setTimestamp(8, Timestamp.from(createdAt));
            statement.setTimestamp(9, Timestamp.from(expiresAt));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Forum boost id was not generated");
                }
                return keys.getLong(1);
            }
        }
    }

    private Aggregate aggregate(Connection connection, long postId, Instant now) throws SQLException {
        String sql = "SELECT initial_heat,created_at,expires_at FROM forum_post_boosts " +
                "WHERE post_id=? AND created_at<=? AND expires_at>?";
        int count = 0;
        double heat = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, postId);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    count++;
                    Instant created = result.getTimestamp("created_at").toInstant();
                    double hours = Math.max(0, Duration.between(created, now).toMillis() / 3_600_000d);
                    heat += result.getInt("initial_heat") * Math.pow(0.5d, hours / 12d);
                }
            }
        }
        return new Aggregate(count, (int) Math.round(heat));
    }

    private record PostState(
            long authorUserId,
            String title,
            String status,
            boolean pinned,
            boolean announcement,
            boolean enabled) {
    }

    private record Aggregate(int count, int heat) {
    }

    private record ExistingBoost(
            long boostId,
            long postId,
            ForumBoostTier tier,
            BigDecimal amount,
            int initialHeat,
            long bankLedgerId,
            String reference,
            BigDecimal balanceAfter) {
    }

    private record Usage(int userOperations, BigDecimal userAmount, BigDecimal postAmount) {
    }
}
