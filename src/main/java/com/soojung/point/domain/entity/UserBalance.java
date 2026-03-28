package com.soojung.point.domain.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserBalance {

    private String userId;
    private Long availablePoint;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
