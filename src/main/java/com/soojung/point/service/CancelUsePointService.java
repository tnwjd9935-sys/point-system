package com.soojung.point.service;

import com.soojung.point.config.PointPolicyProperties;
import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.dto.CancelUsePointRequest;
import com.soojung.point.dto.CancelUsePointResponse;
import com.soojung.point.repository.PointDetailRepository;
import com.soojung.point.repository.PointTradeRepository;
import com.soojung.point.repository.UserBalanceRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
public class CancelUsePointService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private static String internalPo05RequestId(String cancelRequestId, int seq) {
        return "SYS-" + cancelRequestId + "-P05-" + seq;
    }

    private final PointPolicyProperties policy;
    private final PointTradeRepository pointTradeRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final PointDetailRepository pointDetailRepository;
    private final PointKeyGenerator pointKeyGenerator;
    private final EarnPointService earnPointService;

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
        earnPointService.validateExpireRange(expireDate, today);
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
        earnPointService.insertBalanceAfterEarn(userId, amount);
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
