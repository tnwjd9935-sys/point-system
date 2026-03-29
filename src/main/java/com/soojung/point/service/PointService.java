package com.soojung.point.service;

import com.soojung.point.dto.CancelEarnPointRequest;
import com.soojung.point.dto.CancelEarnPointResponse;
import com.soojung.point.dto.CancelUsePointRequest;
import com.soojung.point.dto.CancelUsePointResponse;
import com.soojung.point.dto.EarnPointRequest;
import com.soojung.point.dto.EarnPointResponse;
import com.soojung.point.dto.UsePointRequest;
import com.soojung.point.dto.UsePointResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService {

    private final EarnPointService earnPointService;
    private final CancelEarnPointService cancelEarnPointService;
    private final UsePointService usePointService;
    private final CancelUsePointService cancelUsePointService;

    @Transactional
    public EarnPointResponse earn(EarnPointRequest req) {
        return earnPointService.earn(req);
    }

    @Transactional
    public CancelEarnPointResponse cancelEarn(CancelEarnPointRequest req) {
        return cancelEarnPointService.cancelEarn(req);
    }

    @Transactional
    public UsePointResponse use(UsePointRequest req) {
        return usePointService.use(req);
    }

    @Transactional
    public CancelUsePointResponse cancelUse(CancelUsePointRequest req) {
        return cancelUsePointService.cancelUse(req);
    }
}
