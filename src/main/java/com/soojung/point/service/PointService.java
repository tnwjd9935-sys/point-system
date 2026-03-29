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
import com.soojung.point.dto.UsePointRequest;
import com.soojung.point.dto.UsePointResponse;
import com.soojung.point.repository.PointDetailRepository;
import com.soojung.point.repository.PointTradeRepository;
import com.soojung.point.repository.UserBalanceRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 포인트 적립·사용
@Slf4j
@Service
public class PointService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

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

    // 적립 한 건:  request_id로 맞추고, 잔고, 거래·상세는 한 트랜잭션에서 처리
    @Transactional
    public EarnPointResponse earn(EarnPointRequest req) {
        log.info("적립 요청 시작 requestId={}, userId={}, amount={}", req.getRequestId(), req.getUserId(), req.getAmount());
        try {
            validateBase(req);
            String adminGrantedYn = normalizeAdminGrantedYn(req.getAdminGrantedYn()); // Y/N/null

            // 같은 requestId로 이미 끝난 요청이면 INSERT 없이 예전 결과만 돌려줌
            Optional<PointTrade> existing = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
            if (existing.isPresent()) {
                log.info("기존 적립 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                return toEarnResponse(existing.get(), req);
            }

            LocalDate expireDate = resolveExpireDate(req); // 만료일(날짜 타입)
            validateExpireRange(expireDate, LocalDate.now());
            String expireYmdStr = expireDate.format(YMD); // DB 저장용 문자열
            log.debug("만료일 계산 완료 requestId={}, expireYmd={}", req.getRequestId(), expireYmdStr);

            long amount = req.getAmount(); // 이번 적립액
            assertBalanceLimit(req.getUserId(), amount);

            String pointKey = pointKeyGenerator.nextUniquePointKey(); // 거래 PK

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
            trade.setAdminGrantedYn(adminGrantedYn);

            // 동시 요청이면 request_id unique에서 걸릴 수 있음 → 저장된 행 다시 읽어서 동일 응답
            try {
                pointTradeRepository.insertPointTrade(trade);
            } catch (DataIntegrityViolationException e) {
                PointTrade stored = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
                if (stored != null) {
                    log.info("기존 적립 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                    return toEarnResponse(stored, req);
                }
                throw e;
            }

            log.info("적립 거래 저장 완료 requestId={}, pointKey={}, amount={}", req.getRequestId(), pointKey, amount);

            upsertBalanceAfterEarn(req.getUserId(), amount);
            UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
            log.info("잔고 반영 완료 userId={}, availablePoint={}", req.getUserId(), balance.getAvailablePoint());

            insertEarnDetail(req, pointKey, amount);

            log.info("적립 처리 완료 requestId={}, userId={}, pointKey={}", req.getRequestId(), req.getUserId(), pointKey);

            return new EarnPointResponse(
                    pointKey,
                    req.getUserId(),
                    amount,
                    balance.getAvailablePoint(),
                    TradeType.PO01.name(),
                    req.getRequestId(),
                    "적립 완료"
            );
        } catch (Exception e) {
            log.error(
                    "적립 처리 실패 requestId={}, userId={}, message={}",
                    req != null ? req.getRequestId() : null,
                    req != null ? req.getUserId() : null,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    // 사용 한 건: request_id로 맞추고, 차감·상세·잔고를 한 트랜잭션에서 처리
    @Transactional
    public UsePointResponse use(UsePointRequest req) {
        log.info(
                "사용 요청 시작 requestId={}, userId={}, amount={}, orderNo={}",
                req.getRequestId(),
                req.getUserId(),
                req.getAmount(),
                req.getOrderNo());
        try {
            // 1. 기본 요청값 검증
            validateUseBase(req);

            // 2. 동일 requestId 처리 이력 확인 (POINT_TRADE 기준)
            Optional<PointTrade> existingByRequest = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
            if (existingByRequest.isPresent()) {
                PointTrade existing = existingByRequest.get();
                if (existing.getTradeType() == TradeType.PO02) {
                    log.info("기존 사용 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                    return toUseResponse(existing, req);
                }
                throw new IllegalArgumentException("requestId가 이미 다른 유형의 거래에 사용 중입니다.");
            }

            long amount = req.getAmount();

            // 3. 잔고 확인
            long available =
                    userBalanceRepository.selectUserBalance(req.getUserId()).map(UserBalance::getAvailablePoint).orElse(0L);
            if (available < amount) {
                throw new IllegalStateException("가용 포인트가 부족합니다.");
            }

            // 4. 차감 대상 적립건 조회
            List<PointTrade> candidates = pointTradeRepository.selectAvailableEarnTradesForUse(req.getUserId());
            long sumRemains = candidates.stream().mapToLong(PointTrade::getRemainAmount).sum();
            if (sumRemains < amount) {
                throw new IllegalStateException("차감 가능한 적립 잔량이 부족합니다.");
            }
            log.info(
                    "차감 대상 조회 완료 userId={}, rowCount={}, requestedAmount={}",
                    req.getUserId(),
                    candidates.size(),
                    amount);
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

            String usePointKey = pointKeyGenerator.nextUniquePointKey();
            PointTrade useTrade = buildUsePointTrade(usePointKey, req, amount);

            // 5. 사용 거래 생성
            try {
                pointTradeRepository.save(useTrade);
            } catch (DataIntegrityViolationException e) {
                PointTrade stored =
                        pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
                if (stored != null && stored.getTradeType() == TradeType.PO02) {
                    log.info("기존 사용 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                    return toUseResponse(stored, req);
                }
                throw e;
            }
            log.info("사용 거래 저장 완료 requestId={}, pointKey={}, amount={}", req.getRequestId(), usePointKey, amount);

            // 6. 적립건별 잔여 차감
            for (EarnSlice s : slices) {
                PointTrade earn = s.trade();
                earn.setRemainAmount(earn.getRemainAmount() - s.takeAmount());
                pointTradeRepository.updateRemainAmount(earn);
            }

            // 7. 사용 상세 기록
            for (EarnSlice s : slices) {
                PointDetail detail = new PointDetail(
                        req.getUserId(),
                        usePointKey,
                        TradeType.PO02,
                        s.trade().getPointKey(),
                        usePointKey,
                        s.takeAmount(),
                        req.getOrderNo());
                pointDetailRepository.save(detail);
            }

            // 8. 잔고 반영
            UserBalance balanceRow = userBalanceRepository
                    .selectUserBalance(req.getUserId())
                    .orElseThrow(() -> new IllegalStateException("잔고 정보가 없습니다."));
            balanceRow.setAvailablePoint(balanceRow.getAvailablePoint() - amount);
            userBalanceRepository.updateAvailablePoint(balanceRow);

            UserBalance after = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
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
        }
    }

    private record EarnSlice(PointTrade trade, long takeAmount) {}

    // 필수 필드 + 적립액이 설정 범위검증
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

    // 비었으면 null, 아니면 Y/N만
    private String normalizeAdminGrantedYn(String adminGrantedYn) {
        if (adminGrantedYn == null || adminGrantedYn.isBlank()) {
            return null;
        }
        String v = adminGrantedYn.trim();
        if ("Y".equalsIgnoreCase(v)) {
            return "Y";
        }
        if ("N".equalsIgnoreCase(v)) {
            return "N";
        }
        throw new IllegalArgumentException("관리자 수기 지급 여부는 Y 또는 N 이어야 합니다.");
    }

    // 만료일 안 주면 오늘+defaultExpireDays
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

    // 만료일 정책  (min일 이후 ~ N년 미만)
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

    // 적립 넣고 나면 maxBalance 넘는지
    private void assertBalanceLimit(String userId, long earnAmount) {
        long current = userBalanceRepository.selectUserBalance(userId)
                .map(UserBalance::getAvailablePoint)
                .orElse(0L);
        if (current + earnAmount > policy.maxBalanceAmount()) {
            throw new IllegalStateException("최대 보유 포인트(" + policy.maxBalanceAmount() + ")를 초과합니다.");
        }
    }

    // 첫 적립이면 행 만들고, 있으면 가용 잔고만 올림
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

    // 적립 건당 상세 한 줄 (나중에 사용·상세 추적할 때 묶어서 보려고)
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

    // 사용 요청 필수값·금액 하한
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
                0L,
                req.getOrderNo(),
                null,
                ExpireYn.N,
                null,
                PointStatus.ACTIVE);
        trade.setRequestId(req.getRequestId());
        return trade;
    }

    // 이미 끝난 사용 요청이면 저장된 거래·현재 잔고로 응답 맞춤
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

    // 중복거래 체크 저장돼 있던 거래 + 현재 잔고로 응답 맞춤
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
