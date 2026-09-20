package com.vcampus.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 根据互动、发布时间和有效推广计算论坛帖子热度的纯规则组件。
 *
 * @since 1.0
 */
public final class ForumHeatPolicy {
    private ForumHeatPolicy() {
    }

    public static int currentBoostHeat(
            int initialHeat, Instant createdAt, Instant expiresAt, Instant now) {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (initialHeat < 0) throw new IllegalArgumentException("initialHeat must not be negative");
        if (!now.isBefore(expiresAt)) return 0;
        double hours = Math.max(0d, Duration.between(createdAt, now).toMillis() / 3_600_000d);
        return (int) Math.round(initialHeat * Math.pow(0.5d, hours / 12d));
    }

    public static double score(
            int likes, int comments, int views, int activeBoostHeat, double ageHours) {
        return likes * 4d
                + comments * 8d
                + Math.min(Math.max(views, 0), 1000) * 0.2d
                + Math.max(activeBoostHeat, 0)
                - Math.min(Math.max(ageHours, 0d), 168d) * 2d;
    }
}
