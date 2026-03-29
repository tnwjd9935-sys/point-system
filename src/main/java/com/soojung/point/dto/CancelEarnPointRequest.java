package com.soojung.point.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CancelEarnPointRequest {

    // 이번 적립취소 요청의 중복 처리 방지 키
    private String requestId;

    private String userId;

    // 원 적립(PO01) 조회용 요청 ID
    private String originRequestId;
}
