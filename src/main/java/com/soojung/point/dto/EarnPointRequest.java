package com.soojung.point.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// POST /api/points/earn 요청 바디
@Getter
@Setter
@NoArgsConstructor
public class EarnPointRequest {

    private String userId; // 회원 10자리
    private Long amount; // 적립 포인트
    private String expireYmd; // 만료일 yyyyMMdd, 없으면 서버 기본값
    private String requestId; // 요청 유니크한 키 (클라이언트가 생성)
    private String adminGrantedYn; // 관리자 수기 지급 Y/N, 없으면 null
}
