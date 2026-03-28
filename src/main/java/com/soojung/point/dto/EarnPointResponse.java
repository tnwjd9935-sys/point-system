package com.soojung.point.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 적립 성공 시 내려주는 필드
@Getter
@AllArgsConstructor
public class EarnPointResponse {

    private String pointKey; // 거래 키 5자리
    private String userId;
    private Long amount; // 포인트 금액
    private Long availablePoint; // 적립 반영 후 사용자 총 가용 포인트
    private String tradeType; // 거래타입
    private String requestId; // 요청 유니크한 키 (클라이언트가 생성)
    private String message; // 결과 문구
}
