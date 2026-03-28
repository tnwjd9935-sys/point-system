package com.soojung.point.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "point.policy")
public record PointPolicyProperties(
        long minEarnAmount,
        long maxEarnAmount,
        long maxBalanceAmount,
        int defaultExpireDays,
        int minExpireDaysFromToday,
        int maxExpireYearsFromToday
) {}
