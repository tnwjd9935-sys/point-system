package com.soojung.point.domain.entity;

import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 포인트 거래 원장 한 건 (POINT_TRADE)
@Getter
@Setter
@NoArgsConstructor
public class PointTrade {

    private String pointKey;
    private String userId;
    private TradeType tradeType; //거래 타입 (적립 / 사용 / 취소)
    private Long amount; // 포인트 금액
    private Long remainAmount; // 거래별 남은 처리 가능 금액(적립 잔액·사용 취소 가능액)
    private String orderNo; // 주문번호
    private String originalPointKey; // 원 거래 키
    private ExpireYn expireYn; // 만료 대상 여부
    private String expireYmd; // 만료일 yyyyMMdd
    private PointStatus status; // 거래상태
    private String requestId; // 요청 유니크한 키 (클라이언트가 생성)
    private String adminGrantedYn; // 관리자 수기 지급 여부 Y/N/null
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

    // PO01/PO05이면서 미사용(remain == amount). 적립취소는 서비스에서 PO01만 허용.
    public boolean isUnusedEarnForCancelPolicy() {
        if (tradeType != TradeType.PO01 && tradeType != TradeType.PO05) {
            return false;
        }
        return amount != null && remainAmount != null && amount.equals(remainAmount);
    }
}
