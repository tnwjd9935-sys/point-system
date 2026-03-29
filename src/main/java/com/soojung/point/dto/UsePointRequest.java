package com.soojung.point.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// POST /api/points/use 요청 바디
@Getter
@Setter
@NoArgsConstructor
public class UsePointRequest {

    private String userId; // 회원 10자리
    private String orderNo; // 주문번호 (필수)
    private Long amount; // 사용 포인트
    private String requestId; // 요청 유니크한 키 (클라이언트가 생성)
}
