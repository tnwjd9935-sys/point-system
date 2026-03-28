package com.soojung.point.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class EarnPointRequest {

    private String userId;
    private Long amount;
    private String expireYmd;
    private String requestId;
    private String manualYn;
}
