package com.soojung.point.service;

import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.dto.UsePointRequest;
import com.soojung.point.dto.UsePointResponse;
import com.soojung.point.repository.PointDetailRepository;
import com.soojung.point.repository.PointTradeRepository;
import com.soojung.point.repository.UserBalanceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsePointService {

    private final PointTradeRepository pointTradeRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final PointDetailRepository pointDetailRepository;
    private final PointKeyGenerator pointKeyGenerator;

    public UsePointResponse use(UsePointRequest req) {
        final long startedAt = System.nanoTime();
        log.info(
                "사용 요청 시작 requestId={}, userId={}, amount={}, orderNo={}",
                req.getRequestId(),
                req.getUserId(),
                req.getAmount(),
                req.getOrderNo());
        try {
            logJvmMemory("사용시작");
            validateUseBase(req);

            Optional<UsePointResponse> idem = findIdempotentUseResponse(req);
            if (idem.isPresent()) {
                return idem.get();
            }

            long amount = req.getAmount();
            assertSufficientBalance(req.getUserId(), amount);

            List<PointTrade> candidates = loadEarnCandidatesForUse(req.getUserId(), amount);
            List<EarnSlice> slices = allocateEarnSlices(req, candidates, amount);

            String usePointKey = pointKeyGenerator.nextUniquePointKey();
            Optional<UsePointResponse> dup = insertUseTrade(req, usePointKey, amount);
            if (dup.isPresent()) {
                return dup.get();
            }
            log.info("사용 거래 저장 완료 requestId={}, pointKey={}, amount={}", req.getRequestId(), usePointKey, amount);

            applyEarnDeductions(slices);
            insertUseDetails(req, usePointKey, slices);

            UserBalance after = deductBalance(req.getUserId(), amount);
            log.info("잔고 반영 완료 userId={}, availablePoint={}", req.getUserId(), after.getAvailablePoint());

            log.info("사용 처리 완료 requestId={}, userId={}, pointKey={}", req.getRequestId(), req.getUserId(), usePointKey);

            return new UsePointResponse(
                    usePointKey,
                    req.getUserId(),
                    req.getOrderNo(),
                    amount,
                    after.getAvailablePoint(),
                    TradeType.PO02.name(),
                    req.getRequestId(),
                    "사용 완료");
        } catch (Exception e) {
            log.error(
                    "사용 처리 실패 requestId={}, userId={}, message={}",
                    req != null ? req.getRequestId() : null,
                    req != null ? req.getUserId() : null,
                    e.getMessage(),
                    e);
            throw e;
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("사용 처리 소요 requestId={}, elapsedMs={}", req != null ? req.getRequestId() : null, elapsedMs);
        }
    }

    private record EarnSlice(PointTrade trade, long takeAmount) {}

    private Optional<UsePointResponse> findIdempotentUseResponse(UsePointRequest req) {
        Optional<PointTrade> existingByRequest = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
        if (existingByRequest.isEmpty()) {
            return Optional.empty();
        }
        PointTrade existing = existingByRequest.get();
        if (existing.getTradeType() == TradeType.PO02) {
            log.info("기존 사용 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
            return Optional.of(toUseResponse(existing, req));
        }
        throw new IllegalArgumentException("requestId가 이미 다른 유형의 거래에 사용 중입니다.");
    }

    private void assertSufficientBalance(String userId, long amount) {
        long available = userBalanceRepository.selectUserBalance(userId).map(UserBalance::getAvailablePoint).orElse(0L);
        if (available < amount) {
            throw new IllegalStateException("가용 포인트가 부족합니다.");
        }
    }

    private List<PointTrade> loadEarnCandidatesForUse(String userId, long amount) {
        List<PointTrade> candidates = pointTradeRepository.selectAvailableEarnTradesForUse(userId);
        long sumRemains = candidates.stream().mapToLong(PointTrade::getRemainAmount).sum();
        if (sumRemains < amount) {
            throw new IllegalStateException("차감 가능한 적립 잔량이 부족합니다.");
        }
        log.info("차감 대상 조회 완료 userId={}, rowCount={}, requestedAmount={}", userId, candidates.size(), amount);
        return candidates;
    }

    private List<EarnSlice> allocateEarnSlices(UsePointRequest req, List<PointTrade> candidates, long amount) {
        List<EarnSlice> slices = new ArrayList<>();
        long left = amount;
        for (PointTrade earn : candidates) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(left, earn.getRemainAmount());
            if (take <= 0) {
                continue;
            }
            slices.add(new EarnSlice(earn, take));
            left -= take;
        }
        if (left > 0) {
            throw new IllegalStateException("차감 가능한 적립 잔량이 부족합니다.");
        }
        for (EarnSlice s : slices) {
            log.debug(
                    "차감 배분 requestId={}, fromPointKey={}, amount={}",
                    req.getRequestId(),
                    s.trade().getPointKey(),
                    s.takeAmount());
        }
        return slices;
    }

    private Optional<UsePointResponse> insertUseTrade(UsePointRequest req, String usePointKey, long amount) {
        PointTrade useTrade = buildUsePointTrade(usePointKey, req, amount);
        try {
            pointTradeRepository.insertPointTrade(useTrade);
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            PointTrade stored = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
            if (stored != null && stored.getTradeType() == TradeType.PO02) {
                log.info("기존 사용 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                return Optional.of(toUseResponse(stored, req));
            }
            throw e;
        }
    }

    private void applyEarnDeductions(List<EarnSlice> slices) {
        for (EarnSlice s : slices) {
            PointTrade earn = s.trade();
            earn.setRemainAmount(earn.getRemainAmount() - s.takeAmount());
            pointTradeRepository.updateRemainAmount(earn);
        }
    }

    private void insertUseDetails(UsePointRequest req, String usePointKey, List<EarnSlice> slices) {
        for (EarnSlice s : slices) {
            PointDetail detail = new PointDetail(
                    req.getUserId(),
                    usePointKey,
                    TradeType.PO02,
                    s.trade().getPointKey(),
                    usePointKey,
                    s.takeAmount(),
                    req.getOrderNo());
            pointDetailRepository.insertPointDetail(detail);
        }
    }

    private UserBalance deductBalance(String userId, long amount) {
        UserBalance balanceRow = userBalanceRepository
                .selectUserBalance(userId)
                .orElseThrow(() -> new IllegalStateException("잔고 정보가 없습니다."));
        balanceRow.setAvailablePoint(balanceRow.getAvailablePoint() - amount);
        userBalanceRepository.updateAvailablePoint(balanceRow);
        return userBalanceRepository.selectUserBalance(userId).orElseThrow();
    }

    private void validateUseBase(UsePointRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }
        if (req.getUserId() == null || req.getUserId().length() != 10) {
            throw new IllegalArgumentException("userId는 10자리여야 합니다.");
        }
        if (req.getOrderNo() == null || req.getOrderNo().isBlank()) {
            throw new IllegalArgumentException("orderNo는 필수입니다.");
        }
        if (req.getAmount() == null) {
            throw new IllegalArgumentException("amount는 필수입니다.");
        }
        if (req.getAmount() < 1) {
            throw new IllegalArgumentException("사용 금액은 1 이상이어야 합니다.");
        }
    }

    private PointTrade buildUsePointTrade(String pointKey, UsePointRequest req, long amount) {
        PointTrade trade = new PointTrade(
                pointKey,
                req.getUserId(),
                TradeType.PO02,
                amount,
                amount,
                req.getOrderNo(),
                null,
                ExpireYn.N,
                null,
                PointStatus.PS01);
        trade.setRequestId(req.getRequestId());
        return trade;
    }

    private UsePointResponse toUseResponse(PointTrade trade, UsePointRequest req) {
        if (!trade.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        if (!trade.getAmount().equals(req.getAmount())) {
            throw new IllegalArgumentException("동일 requestId에 대해 금액이 일치하지 않습니다.");
        }
        if (!Objects.equals(trade.getOrderNo(), req.getOrderNo())) {
            throw new IllegalArgumentException("동일 requestId에 대해 주문번호가 일치하지 않습니다.");
        }
        UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
        return new UsePointResponse(
                trade.getPointKey(),
                trade.getUserId(),
                req.getOrderNo(),
                trade.getAmount(),
                balance.getAvailablePoint(),
                TradeType.PO02.name(),
                req.getRequestId(),
                "사용 완료");
    }

    private void logJvmMemory(String phase) {
        if (!log.isDebugEnabled()) {
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long used = total - rt.freeMemory();
        long usagePercent = max > 0 ? (used * 100L) / max : 0L;
        log.debug("jvmMem phase={} used={} total={} max={} usagePercent={}", phase, used, total, max, usagePercent);
    }
}
