package com.soojung.point.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

//사용취소 시 내려주는 필드
@Getter
@AllArgsConstructor
public class CancelUsePointResponse {

    private String pointKey;
    private String userId;
    private String originRequestId;
    private Long canceledAmount;
    private Long availablePoint;
    private String tradeType;
    private String requestId;
    private String message;
}
