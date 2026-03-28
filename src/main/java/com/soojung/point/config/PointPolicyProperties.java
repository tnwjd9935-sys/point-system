package com.soojung.point.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yml의 포인트 정책값
@ConfigurationProperties(prefix = "point.policy")
public record PointPolicyProperties(
        long minEarnAmount, // 최소 적립포인트
        long maxEarnAmount, // 최대 적립포인트(1회)
        long maxBalanceAmount, // 사용자 보유 가능한 최대 포인트
        int defaultExpireDays, // 만료일 미입력 시 디폴트
        int minExpireDaysFromToday, // 만료일이 오늘보다 최소 며칠 뒤여야 하는지
        int maxExpireYearsFromToday // 만료일이 오늘+N년 미만인지 판단할 때 쓰는 N
) {}
