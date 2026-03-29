package com.soojung.point.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 사용 성공 시 내려주는 필드
@Getter
@AllArgsConstructor
public class UsePointResponse {

    private String pointKey; // 사용 거래 키 5자리
    private String userId;
    private String orderNo;
    private Long amount; // 사용 포인트
    private Long availablePoint; // 사용 반영 후 가용 포인트
    private String tradeType; // 거래타입 (PO02)
    private String requestId;
    private String message;
}
