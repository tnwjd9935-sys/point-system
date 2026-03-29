package com.soojung.point.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CancelEarnPointResponse {

    // 적립취소 거래(PO03) point_key
    private String pointKey;

    private String userId;
    private String originRequestId;
    private Long canceledAmount;
    private Long availablePoint;
    private String tradeType;
    private String requestId;
    private String message;
}
