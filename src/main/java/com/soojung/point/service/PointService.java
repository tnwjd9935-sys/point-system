package com.soojung.point.service;

import com.soojung.point.config.PointPolicyProperties;
import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.dto.EarnPointRequest;
import com.soojung.point.dto.EarnPointResponse;
import com.soojung.point.repository.PointDetailRepository;
import com.soojung.point.repository.PointTradeRepository;
import com.soojung.point.repository.UserBalanceRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final PointPolicyProperties policy;
    private final PointTradeRepository pointTradeRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final PointDetailRepository pointDetailRepository;
    private final PointKeyGenerator pointKeyGenerator;

    public PointService(
            PointPolicyProperties policy,
            PointTradeRepository pointTradeRepository,
            UserBalanceRepository userBalanceRepository,
            PointDetailRepository pointDetailRepository,
            PointKeyGenerator pointKeyGenerator
    ) {
        this.policy = policy;
        this.pointTradeRepository = pointTradeRepository;
        this.userBalanceRepository = userBalanceRepository;
        this.pointDetailRepository = pointDetailRepository;
        this.pointKeyGenerator = pointKeyGenerator;
    }

    @Transactional
    public EarnPointResponse earn(EarnPointRequest req) {
        validateBase(req);
        String manualYn = normalizeManualYn(req.getManualYn());

        Optional<PointTrade> existing = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
        if (existing.isPresent()) {
            return toEarnResponse(existing.get(), req);
        }

        LocalDate expireDate = resolveExpireDate(req);
        validateExpireRange(expireDate, LocalDate.now());
        long amount = req.getAmount();
        assertBalanceLimit(req.getUserId(), amount);

        String expireYmdStr = expireDate.format(YMD);
        String pointKey = pointKeyGenerator.nextUniquePointKey();

        PointTrade trade = new PointTrade(
                pointKey,
                req.getUserId(),
                TradeType.PO01,
                amount,
                amount,
                req.getRequestId(),
                null,
                ExpireYn.Y,
                expireYmdStr,
                PointStatus.ACTIVE
        );
        trade.setRequestId(req.getRequestId());
        trade.setManualYn(manualYn);

        try {
            pointTradeRepository.insertPointTrade(trade);
        } catch (DataIntegrityViolationException e) {
            PointTrade stored = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
            if (stored != null) {
                return toEarnResponse(stored, req);
            }
            throw e;
        }

        upsertBalanceAfterEarn(req.getUserId(), amount);
        insertEarnDetail(req, pointKey, amount);

        UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
        return new EarnPointResponse(
                pointKey,
                req.getUserId(),
                amount,
                balance.getAvailablePoint(),
                TradeType.PO01.name(),
                req.getRequestId(),
                "적립 완료"
        );
    }

    //필수 값 및 적립 금액범위 검증
    private void validateBase(EarnPointRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }
        if (req.getUserId() == null || req.getUserId().length() != 10) {
            throw new IllegalArgumentException("userId는 10자리여야 합니다.");
        }
        if (req.getAmount() == null) {
            throw new IllegalArgumentException("amount는 필수입니다.");
        }
        long amount = req.getAmount();
        if (amount < policy.minEarnAmount() || amount > policy.maxEarnAmount()) {
            throw new IllegalArgumentException(
                    "적립 금액은 " + policy.minEarnAmount() + " 이상 " + policy.maxEarnAmount() + " 이하여야 합니다.");
        }
    }

    private String normalizeManualYn(String manualYn) {
        if (manualYn == null || manualYn.isBlank()) {
            return null;
        }
        String v = manualYn.trim();
        if ("Y".equalsIgnoreCase(v)) {
            return "Y";
        }
        if ("N".equalsIgnoreCase(v)) {
            return "N";
        }
        throw new IllegalArgumentException("manualYn은 Y 또는 N 이어야 합니다.");
    }

    private LocalDate resolveExpireDate(EarnPointRequest req) {
        if (req.getExpireYmd() == null || req.getExpireYmd().isBlank()) {
            return LocalDate.now().plusDays(policy.defaultExpireDays());
        }
        String raw = req.getExpireYmd().trim();
        if (raw.length() != 8) {
            throw new IllegalArgumentException("expireYmd는 yyyyMMdd 8자리 형식이어야 합니다.");
        }
        return LocalDate.parse(raw, YMD);
    }

    private void validateExpireRange(LocalDate expire, LocalDate today) {
        LocalDate minDate = today.plusDays(policy.minExpireDaysFromToday());
        LocalDate maxExclusive = today.plusYears(policy.maxExpireYearsFromToday());
        if (expire.isBefore(minDate)) {
            throw new IllegalArgumentException("만료일은 오늘 기준 최소 " + policy.minExpireDaysFromToday() + "일 후여야 합니다.");
        }
        if (!expire.isBefore(maxExclusive)) {
            throw new IllegalArgumentException("만료일은 오늘으로부터 " + policy.maxExpireYearsFromToday() + "년 미만이어야 합니다.");
        }
    }

    private void assertBalanceLimit(String userId, long earnAmount) {
        long current = userBalanceRepository.selectUserBalance(userId)
                .map(UserBalance::getAvailablePoint)
                .orElse(0L);
        if (current + earnAmount > policy.maxBalanceAmount()) {
            throw new IllegalStateException("최대 보유 포인트(" + policy.maxBalanceAmount() + ")를 초과합니다.");
        }
    }

    private void upsertBalanceAfterEarn(String userId, long delta) {
        Optional<UserBalance> opt = userBalanceRepository.selectUserBalance(userId);
        if (opt.isEmpty()) {
            userBalanceRepository.insertUserBalance(new UserBalance(userId, delta));
        } else {
            UserBalance ub = opt.get();
            ub.setAvailablePoint(ub.getAvailablePoint() + delta);
            userBalanceRepository.updateAvailablePoint(ub);
        }
    }

    private void insertEarnDetail(EarnPointRequest req, String pointKey, long amount) {
        PointDetail detail = new PointDetail(
                req.getUserId(),
                pointKey,
                TradeType.PO01,
                null,
                pointKey,
                amount,
                req.getRequestId()
        );
        pointDetailRepository.insertPointDetail(detail);
    }

    private EarnPointResponse toEarnResponse(PointTrade trade, EarnPointRequest req) {
        if (!trade.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        if (!trade.getAmount().equals(req.getAmount())) {
            throw new IllegalArgumentException("동일 requestId에 대해 금액이 일치하지 않습니다.");
        }
        UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
        return new EarnPointResponse(
                trade.getPointKey(),
                trade.getUserId(),
                trade.getAmount(),
                balance.getAvailablePoint(),
                trade.getTradeType().name(),
                req.getRequestId(),
                "적립 완료"
        );
    }
}
