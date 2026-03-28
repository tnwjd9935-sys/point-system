package com.soojung.point.domain.entity;

import com.soojung.point.domain.enums.TradeType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 거래 한 건의 세부 흐름 (POINT_DETAIL)
@Getter
@Setter
@NoArgsConstructor
public class PointDetail {

    private Long detailId; // 내역 PK
    private String userId;
    private String pointKey;
    private TradeType tradeType; //거래 타입 (적립 / 사용 / 취소)
    private String sourcePointKey; // 사용 시 차감된 포인트키
    private String targetPointKey; // 사용/취소 후 영향을 받은 대상 포인트키
    private Long amount; // 포인트
    private String orderNo; // 주문번호
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
