package com.soojung.point.domain.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 사용자별 가용 포인트 합계 (USER_BALANCE 테이블)
@Getter
@Setter
@NoArgsConstructor
public class UserBalance {

    private String userId; // PK, 10자리
    private Long availablePoint; // 포인트 잔액
    private LocalDateTime createdAt; // 최초 적립 시각
    private LocalDateTime updatedAt; // 마지막 변경 시각

    public UserBalance(String userId, Long availablePoint) {
        this.userId = userId;
        this.availablePoint = availablePoint != null ? availablePoint : 0L;
    }

    public void onBeforeInsert() {
        if (this.availablePoint == null) {
            this.availablePoint = 0L;
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void onBeforeUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
