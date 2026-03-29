package com.soojung.point.service;

import com.soojung.point.config.PointPolicyProperties;
import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.dto.CancelEarnPointRequest;
import com.soojung.point.dto.CancelEarnPointResponse;
import com.soojung.point.dto.CancelUsePointRequest;
import com.soojung.point.dto.CancelUsePointResponse;
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

@Slf4j
@Service
public class PointService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    // PO05용 내부 requestId
    private static String internalPo05RequestId(String cancelRequestId, int seq) {
        return "SYS-" + cancelRequestId + "-P05-" + seq;
    }

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
        log.info("적립 요청 시작 requestId={}, userId={}, amount={}", req.getRequestId(), req.getUserId(), req.getAmount());
        try {
            validateBase(req);
            String adminGrantedYn = normalizeAdminGrantedYn(req.getAdminGrantedYn()); // Y/N/null

            Optional<PointTrade> existing = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
            if (existing.isPresent()) {
                log.info("기존 적립 거래 재사용 requestId={}, userId={}", req.getRequestId(), req.getUserId());
                return toEarnResponse(existing.get(), req);
            }

            LocalDate expireDate = resolveExpireDate(req);
            validateExpireRange(expireDate, LocalDate.now());
            String expireYmdStr = expireDate.format(YMD);
            log.debug("만료일 계산 완료 requestId={}, expireYmd={}", req.getRequestId(), expireYmdStr);

            long amount = req.getAmount();
            assertBalanceLimit(req.getUserId(), amount);

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
                    PointStatus.PS01
            );
            trade.setRequestId(req.getRequestId());
            trade.setAdminGrantedYn(adminGrantedYn);

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

            insertBalanceAfterEarn(req.getUserId(), amount);
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

    @Transactional
    public CancelEarnPointResponse cancelEarn(CancelEarnPointRequest req) {
        log.info(
                "적립취소 시작 requestId={}, originRequestId={}, userId={}",
                req.getRequestId(),
                req.getOriginRequestId(),
                req.getUserId());
        try {
            validateCancelEarnBase(req);

            Optional<PointTrade> existingCancel = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
            if (existingCancel.isPresent()) {
                PointTrade row = existingCancel.get();
                if (row.getTradeType() == TradeType.PO03) {
                    log.info("적립취소 재조회 requestId={}, pointKey={}", req.getRequestId(), row.getPointKey());
                    return toCancelEarnResponse(row, req);
                }
                throw new IllegalArgumentException("requestId가 이미 다른 유형의 거래에 사용 중입니다.");
            }

            PointTrade earn = pointTradeRepository
                    .selectPointTradeByRequestId(req.getOriginRequestId())
                    .orElseThrow(() -> new IllegalArgumentException("원 적립 거래를 찾을 수 없습니다."));

            if (earn.getTradeType() != TradeType.PO01) {
                throw new IllegalArgumentException("적립취소는 원 적립(PO01)만 가능합니다.");
            }
            if (!earn.getUserId().equals(req.getUserId())) {
                throw new IllegalArgumentException("userId가 원 적립 거래와 일치하지 않습니다.");
            }
            if (earn.getStatus() != PointStatus.PS01) {
                throw new IllegalStateException("취소할 수 없는 적립 상태입니다.");
            }
            if (!earn.isUnusedEarnForCancelPolicy()) {
                throw new IllegalStateException("일부 사용된 적립은 적립취소할 수 없습니다.");
            }

            long cancelAmt = earn.getAmount();
            UserBalance balanceRow = userBalanceRepository
                    .selectUserBalance(req.getUserId())
                    .orElseThrow(() -> new IllegalStateException("잔고 정보가 없습니다."));
            if (balanceRow.getAvailablePoint() < cancelAmt) {
                throw new IllegalStateException("가용 포인트가 부족하여 적립취소할 수 없습니다.");
            }

            pointTradeRepository.updateRemainAmountAndStatus(earn.getPointKey(), 0L, PointStatus.PS03);
            log.info(
                    "적립취소 원건 무효 originRequestId={}, earnPointKey={}, amount={}",
                    req.getOriginRequestId(),
                    earn.getPointKey(),
                    cancelAmt);

            balanceRow.setAvailablePoint(balanceRow.getAvailablePoint() - cancelAmt);
            userBalanceRepository.updateAvailablePoint(balanceRow);

            String cancelPointKey = pointKeyGenerator.nextUniquePointKey();
            PointTrade po03 = buildCancelEarnTrade(cancelPointKey, req, earn, cancelAmt);
            try {
                pointTradeRepository.insertPointTrade(po03);
            } catch (DataIntegrityViolationException ex) {
                PointTrade stored = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
                if (stored != null && stored.getTradeType() == TradeType.PO03) {
                    log.info("적립취소 중복키 requestId={}, pointKey={}", req.getRequestId(), stored.getPointKey());
                    return toCancelEarnResponse(stored, req);
                }
                throw ex;
            }

            PointDetail detail = new PointDetail(
                    req.getUserId(),
                    cancelPointKey,
                    TradeType.PO03,
                    earn.getPointKey(),
                    cancelPointKey,
                    cancelAmt,
                    req.getOriginRequestId());
            pointDetailRepository.insertPointDetail(detail);

            UserBalance after = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
            log.info(
                    "적립취소 완료 requestId={}, originRequestId={}, po03Key={}, amount={}",
                    req.getRequestId(),
                    req.getOriginRequestId(),
                    cancelPointKey,
                    cancelAmt);

            return new CancelEarnPointResponse(
                    cancelPointKey,
                    req.getUserId(),
                    req.getOriginRequestId(),
                    cancelAmt,
                    after.getAvailablePoint(),
                    TradeType.PO03.name(),
                    req.getRequestId(),
                    "적립취소 완료");
        } catch (Exception e) {
            log.error(
                    "적립취소 실패 requestId={}, originRequestId={}, userId={}, msg={}",
                    req != null ? req.getRequestId() : null,
                    req != null ? req.getOriginRequestId() : null,
                    req != null ? req.getUserId() : null,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    private void validateCancelEarnBase(CancelEarnPointRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }
        if (req.getUserId() == null || req.getUserId().length() != 10) {
            throw new IllegalArgumentException("userId는 10자리여야 합니다.");
        }
        if (req.getOriginRequestId() == null || req.getOriginRequestId().isBlank()) {
            throw new IllegalArgumentException("originRequestId는 필수입니다.");
        }
    }

    private PointTrade buildCancelEarnTrade(String pointKey, CancelEarnPointRequest req, PointTrade earn, long cancelAmt) {
        PointTrade trade = new PointTrade(
                pointKey,
                req.getUserId(),
                TradeType.PO03,
                cancelAmt,
                0L,
                req.getOriginRequestId(),
                earn.getPointKey(),
                ExpireYn.N,
                null,
                PointStatus.PS01);
        trade.setRequestId(req.getRequestId());
        return trade;
    }

    private CancelEarnPointResponse toCancelEarnResponse(PointTrade po03, CancelEarnPointRequest req) {
        if (!po03.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        if (po03.getTradeType() != TradeType.PO03) {
            throw new IllegalArgumentException("저장된 거래 유형이 적립취소(PO03)가 아닙니다.");
        }
        String earnPk = po03.getOriginalPointKey();
        if (earnPk == null || earnPk.isBlank()) {
            throw new IllegalStateException("적립취소 거래에 원 적립 point_key가 없습니다.");
        }
        PointTrade earn = pointTradeRepository.selectPointTradeByPointKey(earnPk).orElseThrow(() -> new IllegalStateException("원 적립 거래를 찾을 수 없습니다."));
        if (!Objects.equals(earn.getRequestId(), req.getOriginRequestId())) {
            throw new IllegalArgumentException("동일 requestId에 대해 originRequestId가 일치하지 않습니다.");
        }
        if (!earn.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        if (!po03.getAmount().equals(earn.getAmount())) {
            throw new IllegalArgumentException("동일 requestId에 대해 취소 금액이 일치하지 않습니다.");
        }
        UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
        return new CancelEarnPointResponse(
                po03.getPointKey(),
                req.getUserId(),
                req.getOriginRequestId(),
                po03.getAmount(),
                balance.getAvailablePoint(),
                TradeType.PO03.name(),
                req.getRequestId(),
                "적립취소 완료");
    }

    @Transactional
    public UsePointResponse use(UsePointRequest req) {
        log.info(
                "사용 요청 시작 requestId={}, userId={}, amount={}, orderNo={}",
                req.getRequestId(),
                req.getUserId(),
                req.getAmount(),
                req.getOrderNo());
        try {
            logJvmMemory("사용시작");
            validateUseBase(req);

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

            long available =
                    userBalanceRepository.selectUserBalance(req.getUserId()).map(UserBalance::getAvailablePoint).orElse(0L);
            if (available < amount) {
                throw new IllegalStateException("가용 포인트가 부족합니다.");
            }

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

            try {
                pointTradeRepository.insertPointTrade(useTrade);
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

            for (EarnSlice s : slices) {
                PointTrade earn = s.trade();
                earn.setRemainAmount(earn.getRemainAmount() - s.takeAmount());
                pointTradeRepository.updateRemainAmount(earn);
            }

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

    private void insertBalanceAfterEarn(String userId, long delta) {
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

    @Transactional
    public CancelUsePointResponse cancelUse(CancelUsePointRequest req) {
        log.info(
                "사용취소 시작 requestId={}, originRequestId={}, userId={}, amount={}",
                req.getRequestId(),
                req.getOriginRequestId(),
                req.getUserId(),
                req.getAmount());
        try {
            logJvmMemory("사용취소시작");
            validateCancelBase(req);
            long cancelAmt = req.getAmount();

            Optional<PointTrade> existingByCancelRequest = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId());
            if (existingByCancelRequest.isPresent()) {
                PointTrade row = existingByCancelRequest.get();
                if (row.getTradeType() == TradeType.PO04) {
                    log.info("사용취소 재조회 requestId={}, pointKey={}", req.getRequestId(), row.getPointKey());
                    return toCancelUseResponse(row, req);
                }
                throw new IllegalArgumentException("requestId가 이미 다른 유형의 거래에 사용 중입니다.");
            }

            PointTrade useTrade = pointTradeRepository
                    .selectPointTradeByRequestId(req.getOriginRequestId())
                    .orElseThrow(() -> new IllegalArgumentException("원 사용 거래를 찾을 수 없습니다."));

            if (useTrade.getTradeType() != TradeType.PO02) {
                throw new IllegalArgumentException("원 거래가 사용(PO02)이 아닙니다.");
            }
            if (!useTrade.getUserId().equals(req.getUserId())) {
                throw new IllegalArgumentException("userId가 원 사용 거래와 일치하지 않습니다.");
            }
            if (useTrade.getStatus() == PointStatus.PS04) {
                throw new IllegalStateException("이미 전액 취소된 사용 거래입니다.");
            }
            if (useTrade.getStatus() != PointStatus.PS01) {
                throw new IllegalStateException("취소할 수 없는 사용 거래 상태입니다.");
            }
            if (useTrade.getRemainAmount() <= 0) {
                throw new IllegalStateException("남은 취소 가능 금액이 없습니다.");
            }
            if (useTrade.getRemainAmount() < cancelAmt) {
                throw new IllegalStateException("남은 취소 가능 금액보다 큰 금액을 취소할 수 없습니다.");
            }

            List<PointDetail> po02Rows = pointDetailRepository.selectDetailsForUse(useTrade.getPointKey());
            long detailSum = po02Rows.stream().mapToLong(PointDetail::getAmount).sum();
            if (detailSum != useTrade.getAmount()) {
                log.error(
                        "사용취소 상세합 불일치 originRequestId={}, usePointKey={}, detailSum={}, useAmount={}",
                        req.getOriginRequestId(),
                        useTrade.getPointKey(),
                        detailSum,
                        useTrade.getAmount());
                throw new IllegalStateException("사용 상세와 원 거래 금액이 일치하지 않습니다. 운영 확인이 필요합니다.");
            }
            if (po02Rows.isEmpty() && useTrade.getAmount() > 0) {
                throw new IllegalStateException("사용 상세가 없습니다. 운영 확인이 필요합니다.");
            }

            long sumPo04 = pointTradeRepository.sumPo04AmountByOriginalPointKey(useTrade.getPointKey());
            long accountedCanceled = useTrade.getAmount() - useTrade.getRemainAmount();
            if (sumPo04 != accountedCanceled) {
                log.error(
                        "사용취소 PO04합 불일치 usePointKey={}, sumPo04={}, accountedCanceled={}",
                        useTrade.getPointKey(),
                        sumPo04,
                        accountedCanceled);
                throw new IllegalStateException("원 사용 건의 취소 이력과 남은 취소 가능 금액이 맞지 않습니다. 운영 확인이 필요합니다.");
            }

            long[] rowAmounts = po02Rows.stream().mapToLong(PointDetail::getAmount).toArray();
            long[] fifoEarlier = fifoCanceledPerPo02Line(rowAmounts, accountedCanceled);

            String cancelPointKey = pointKeyGenerator.nextUniquePointKey();
            PointTrade cancelTrade = buildCancelPointTrade(cancelPointKey, req, useTrade, cancelAmt);
            try {
                pointTradeRepository.insertPointTrade(cancelTrade);
            } catch (DataIntegrityViolationException ex) {
                PointTrade stored = pointTradeRepository.selectPointTradeByRequestId(req.getRequestId()).orElse(null);
                if (stored != null && stored.getTradeType() == TradeType.PO04) {
                    log.info("사용취소 중복키 requestId={}, pointKey={}", req.getRequestId(), stored.getPointKey());
                    return toCancelUseResponse(stored, req);
                }
                throw ex;
            }

            LocalDate today = LocalDate.now();
            long left = cancelAmt;
            int po05Seq = 0;
            for (int i = 0; i < po02Rows.size() && left > 0; i++) {
                long rowAvail = rowAmounts[i] - fifoEarlier[i];
                if (rowAvail <= 0) {
                    continue;
                }
                long take = Math.min(rowAvail, left);
                PointDetail line = po02Rows.get(i);
                String sourcePointKey = line.getSourcePointKey();
                if (sourcePointKey == null || sourcePointKey.isBlank()) {
                    throw new IllegalStateException("사용 상세에 source_point_key가 없습니다.");
                }

                PointTrade earn = pointTradeRepository
                        .selectPointTradeByPointKey(sourcePointKey)
                        .orElseThrow(() -> new IllegalStateException("차감된 적립 건을 찾을 수 없습니다. sourcePointKey=" + sourcePointKey));
                if (earn.getTradeType() != TradeType.PO01 && earn.getTradeType() != TradeType.PO05) {
                    throw new IllegalStateException("복원 대상이 적립/재적립 거래가 아닙니다. pointKey=" + sourcePointKey);
                }
                if (!earn.getUserId().equals(req.getUserId())) {
                    throw new IllegalStateException("적립 건의 사용자가 일치하지 않습니다. pointKey=" + sourcePointKey);
                }

                String targetKey;
                if (isEarnExpiredForCancel(earn, today)) {
                    PointTrade po05 = insertPo05FromCancel(req.getUserId(), take, req.getRequestId(), po05Seq++, today);
                    targetKey = po05.getPointKey();
                    log.info(
                            "사용취소 PO05 userId={}, amount={}, po05Key={}, sourcePointKey={}",
                            req.getUserId(),
                            take,
                            targetKey,
                            sourcePointKey);
                } else {
                    long remainAfter = earn.getRemainAmount() + take;
                    if (remainAfter > earn.getAmount()) {
                        log.error(
                                "사용취소 remain 초과 originRequestId={}, sourcePointKey={}, earnAmount={}, remainAfter={}",
                                req.getOriginRequestId(),
                                sourcePointKey,
                                earn.getAmount(),
                                remainAfter);
                        throw new IllegalStateException("복원 결과가 적립 한도를 초과합니다. 데이터 정합성을 확인해 주세요.");
                    }
                    earn.setRemainAmount(remainAfter);
                    pointTradeRepository.updateRemainAmount(earn);
                    targetKey = sourcePointKey;
                    log.info(
                            "사용취소 적립복원 userId={}, sourcePointKey={}, delta={}, after={}",
                            req.getUserId(),
                            sourcePointKey,
                            take,
                            remainAfter);
                }

                PointDetail cancelDetail = new PointDetail(
                        req.getUserId(),
                        cancelPointKey,
                        TradeType.PO04,
                        sourcePointKey,
                        targetKey,
                        take,
                        useTrade.getOrderNo());
                pointDetailRepository.insertPointDetail(cancelDetail);
                left -= take;
            }

            if (left > 0) {
                throw new IllegalStateException("취소 배분 실패: 상세 순서 기준으로 취소할 잔량이 부족합니다.");
            }

            UserBalance balanceRow = userBalanceRepository
                    .selectUserBalance(req.getUserId())
                    .orElseThrow(() -> new IllegalStateException("잔고 정보가 없습니다."));
            balanceRow.setAvailablePoint(balanceRow.getAvailablePoint() + cancelAmt);
            userBalanceRepository.updateAvailablePoint(balanceRow);
            log.info("사용취소 잔고 userId={}, delta={}, after={}", req.getUserId(), cancelAmt, balanceRow.getAvailablePoint());

            long newRemain = useTrade.getRemainAmount() - cancelAmt;
            useTrade.setRemainAmount(newRemain);
            pointTradeRepository.updateRemainAmount(useTrade);
            if (newRemain > 0) {
                pointTradeRepository.updateTradeStatus(useTrade.getPointKey(), PointStatus.PS01);
            } else {
                pointTradeRepository.updateTradeStatus(useTrade.getPointKey(), PointStatus.PS04);
            }
            log.info(
                    "사용취소 PO02 usePointKey={}, remainAfter={}, status={}",
                    useTrade.getPointKey(),
                    newRemain,
                    newRemain > 0 ? PointStatus.PS01.getCode() : PointStatus.PS04.getCode());

            UserBalance after = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
            log.info(
                    "사용취소 완료 requestId={}, originRequestId={}, cancelPointKey={}, userId={}",
                    req.getRequestId(),
                    req.getOriginRequestId(),
                    cancelPointKey,
                    req.getUserId());

            return new CancelUsePointResponse(
                    cancelPointKey,
                    req.getUserId(),
                    req.getOriginRequestId(),
                    cancelAmt,
                    after.getAvailablePoint(),
                    TradeType.PO04.name(),
                    req.getRequestId(),
                    "사용취소 완료");
        } catch (Exception e) {
            log.error(
                    "사용취소 실패 requestId={}, originRequestId={}, userId={}, msg={}",
                    req != null ? req.getRequestId() : null,
                    req != null ? req.getOriginRequestId() : null,
                    req != null ? req.getUserId() : null,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    private void validateCancelBase(CancelUsePointRequest req) {
        if (req.getRequestId() == null || req.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId는 필수입니다.");
        }
        if (req.getUserId() == null || req.getUserId().length() != 10) {
            throw new IllegalArgumentException("userId는 10자리여야 합니다.");
        }
        if (req.getOriginRequestId() == null || req.getOriginRequestId().isBlank()) {
            throw new IllegalArgumentException("originRequestId는 필수입니다.");
        }
        if (req.getAmount() == null) {
            throw new IllegalArgumentException("amount는 필수입니다.");
        }
        if (req.getAmount() < 1) {
            throw new IllegalArgumentException("취소 금액은 1 이상이어야 합니다.");
        }
    }

    // 기존 취소분을 PO02 상세 행별로 FIFO 배분한 누적액
    private static long[] fifoCanceledPerPo02Line(long[] rowAmounts, long totalCanceledEarlier) {
        long[] applied = new long[rowAmounts.length];
        long left = totalCanceledEarlier;
        for (int i = 0; i < rowAmounts.length; i++) {
            long take = Math.min(rowAmounts[i], left);
            applied[i] = take;
            left -= take;
        }
        return applied;
    }

    private boolean isEarnExpiredForCancel(PointTrade earn, LocalDate today) {
        if (earn.getExpireYn() != ExpireYn.Y) {
            return false;
        }
        String ymd = earn.getExpireYmd();
        if (ymd == null || ymd.isBlank()) {
            return false;
        }
        LocalDate exp = LocalDate.parse(ymd.trim(), YMD);
        return today.isAfter(exp);
    }

    // 만료 적립 → PO05 재적립. maxBalance 미적용. requestId는 internalPo05RequestId.
    private PointTrade insertPo05FromCancel(String userId, long amount, String cancelRequestId, int seq, LocalDate today) {
        LocalDate expireDate = today.plusDays(policy.defaultExpireDays());
        validateExpireRange(expireDate, today);
        String expireYmdStr = expireDate.format(YMD);
        String pointKey = pointKeyGenerator.nextUniquePointKey();
        String po05RequestId = internalPo05RequestId(cancelRequestId, seq);
        PointTrade trade = new PointTrade(
                pointKey,
                userId,
                TradeType.PO05,
                amount,
                amount,
                cancelRequestId,
                null,
                ExpireYn.Y,
                expireYmdStr,
                PointStatus.PS01);
        trade.setRequestId(po05RequestId);
        pointTradeRepository.insertPointTrade(trade);
        insertBalanceAfterEarn(userId, amount);
        PointDetail detail = new PointDetail(userId, pointKey, TradeType.PO05, null, pointKey, amount, po05RequestId);
        pointDetailRepository.insertPointDetail(detail);
        return trade;
    }

    private PointTrade buildCancelPointTrade(String pointKey, CancelUsePointRequest req, PointTrade useTrade, long cancelAmt) {
        PointTrade trade = new PointTrade(
                pointKey,
                req.getUserId(),
                TradeType.PO04,
                cancelAmt,
                0L,
                useTrade.getOrderNo(),
                useTrade.getPointKey(),
                ExpireYn.N,
                null,
                PointStatus.PS01);
        trade.setRequestId(req.getRequestId());
        return trade;
    }

    private CancelUsePointResponse toCancelUseResponse(PointTrade po04, CancelUsePointRequest req) {
        if (!po04.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        if (po04.getTradeType() != TradeType.PO04) {
            throw new IllegalArgumentException("저장된 거래 유형이 사용취소(PO04)가 아닙니다.");
        }
        if (!po04.getAmount().equals(req.getAmount())) {
            throw new IllegalArgumentException("동일 requestId에 대해 취소 금액이 일치하지 않습니다.");
        }
        String originalPk = po04.getOriginalPointKey();
        if (originalPk == null || originalPk.isBlank()) {
            throw new IllegalStateException("사용취소 거래에 원 사용 point_key가 없습니다.");
        }
        PointTrade use = pointTradeRepository
                .selectPointTradeByPointKey(originalPk)
                .orElseThrow(() -> new IllegalStateException("원 사용 거래를 찾을 수 없습니다."));
        if (!Objects.equals(use.getRequestId(), req.getOriginRequestId())) {
            throw new IllegalArgumentException("동일 requestId에 대해 originRequestId가 일치하지 않습니다.");
        }
        if (!use.getUserId().equals(req.getUserId())) {
            throw new IllegalArgumentException("requestId가 다른 사용자 요청과 충돌합니다.");
        }
        UserBalance balance = userBalanceRepository.selectUserBalance(req.getUserId()).orElseThrow();
        return new CancelUsePointResponse(
                po04.getPointKey(),
                req.getUserId(),
                req.getOriginRequestId(),
                po04.getAmount(),
                balance.getAvailablePoint(),
                TradeType.PO04.name(),
                req.getRequestId(),
                "사용취소 완료");
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
