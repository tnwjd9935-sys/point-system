package com.soojung.point.domain.entity;

import com.soojung.point.domain.enums.TradeType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PointDetail {

    private Long detailId;
    private String userId;
    private String pointKey;
    private TradeType tradeType;
    private String sourcePointKey;
    private String targetPointKey;
    private Long amount;
    private String orderNo;
    private LocalDateTime createdAt;

    public PointDetail(
            String userId,
            String pointKey,
            TradeType tradeType,
            String sourcePointKey,
            String targetPointKey,
            Long amount,
            String orderNo
    ) {
        this.userId = userId;
        this.pointKey = pointKey;
        this.tradeType = tradeType;
        this.sourcePointKey = sourcePointKey;
        this.targetPointKey = targetPointKey;
        this.amount = amount;
        this.orderNo = orderNo;
    }

    public void onBeforeInsert() {
        this.createdAt = LocalDateTime.now();
    }
}
