package com.soojung.point.domain.entity;

import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PointTrade {

    private String pointKey;
    private String userId;
    private TradeType tradeType;
    private Long amount;
    private Long remainAmount;
    private String orderNo;
    private String originalPointKey;
    private ExpireYn expireYn;
    private String expireYmd;
    private PointStatus status;
    private String requestId;
    private String manualYn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public PointTrade(
            String pointKey,
            String userId,
            TradeType tradeType,
            Long amount,
            Long remainAmount,
            String orderNo,
            String originalPointKey,
            ExpireYn expireYn,
            String expireYmd,
            PointStatus status
    ) {
        this.pointKey = pointKey;
        this.userId = userId;
        this.tradeType = tradeType;
        this.amount = amount;
        this.remainAmount = remainAmount;
        this.orderNo = orderNo;
        this.originalPointKey = originalPointKey;
        this.expireYn = expireYn;
        this.expireYmd = expireYmd;
        this.status = status;
    }

    public void onBeforeInsert() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void onBeforeUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
