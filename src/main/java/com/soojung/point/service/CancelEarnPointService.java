package com.soojung.point.service;

import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.dto.CancelEarnPointRequest;
import com.soojung.point.dto.CancelEarnPointResponse;
import com.soojung.point.repository.PointDetailRepository;
import com.soojung.point.repository.PointTradeRepository;
import com.soojung.point.repository.UserBalanceRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelEarnPointService {

    private final PointTradeRepository pointTradeRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final PointDetailRepository pointDetailRepository;
    private final PointKeyGenerator pointKeyGenerator;

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
}
