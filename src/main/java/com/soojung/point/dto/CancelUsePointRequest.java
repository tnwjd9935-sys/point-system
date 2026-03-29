package com.soojung.point.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// POST /api/points/use/cancel 요청 바디
@Getter
@Setter
@NoArgsConstructor
public class CancelUsePointRequest {

    // 취소 요청의 중복 처리 방지 키 (POINT_TRADE.request_id)
    private String requestId;

    private String userId;

    // 사용(PO02) 건을 찾을 때 쓰는 요청 ID
    private String originRequestId;

    // 이번에 취소할 금액 (부분취소)
    private Long amount;
}
