package com.soojung.point.controller;

import com.soojung.point.dto.CancelUsePointRequest;
import com.soojung.point.dto.CancelUsePointResponse;
import com.soojung.point.dto.EarnPointRequest;
import com.soojung.point.dto.EarnPointResponse;
import com.soojung.point.dto.UsePointRequest;
import com.soojung.point.dto.UsePointResponse;
import com.soojung.point.service.PointService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 포인트 API
@RestController
@RequestMapping("/api/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    // 적립 API
    @PostMapping("/earn")
    public EarnPointResponse earn(@RequestBody EarnPointRequest request) {
        return pointService.earn(request);
    }

    // 사용 API
    @PostMapping("/use")
    public UsePointResponse use(@RequestBody UsePointRequest request) {
        return pointService.use(request);
    }

    // 사용취소 API
    @PostMapping("/use/cancel")
    public CancelUsePointResponse cancelUse(@RequestBody CancelUsePointRequest request) {
        return pointService.cancelUse(request);
    }

    // 검증 실패
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
    }
}
