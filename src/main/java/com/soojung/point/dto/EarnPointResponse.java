package com.soojung.point.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EarnPointResponse {

    private String pointKey;
    private String userId;
    private Long amount;
    private Long availablePoint;
    private String tradeType;
    private String requestId;
    private String message;
}
