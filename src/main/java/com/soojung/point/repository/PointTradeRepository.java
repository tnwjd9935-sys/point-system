package com.soojung.point.repository;

import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.repository.mapper.PointTradeRowMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// POINT_TRADE 테이블 전용 JdbcTemplate 접근
@Repository
public class PointTradeRepository {

    private static final Logger queryLog = LoggerFactory.getLogger("QUERY_LOG");

    private final JdbcTemplate jdbcTemplate;
    private static final PointTradeRowMapper ROW_MAPPER = new PointTradeRowMapper();

    public PointTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertPointTrade(PointTrade entity) {
        if (entity.getCreatedAt() == null) {
            entity.onBeforeInsert();
        }
        queryLog.debug(
                "INSERT POINT_TRADE pointKey={}, userId={}, tradeType={}, amount={}, requestId={}",
                entity.getPointKey(),
                entity.getUserId(),
                entity.getTradeType(),
                entity.getAmount(),
                entity.getRequestId());
        jdbcTemplate.update(
                """
                INSERT INTO POINT_TRADE (
                    point_key,
                    user_id,
                    trade_type,
                    amount,
                    remain_amount,
                    order_no,
                    original_point_key,
                    expire_yn,
                    expire_ymd,
                    status,
                    request_id,
                    admin_granted_yn,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                entity.getPointKey(),
                entity.getUserId(),
                entity.getTradeType().name(),
                entity.getAmount(),
                entity.getRemainAmount(),
                entity.getOrderNo(),
                entity.getOriginalPointKey(),
                entity.getExpireYn().name(),
                entity.getExpireYmd(),
                entity.getStatus().getCode(),
                entity.getRequestId(),
                entity.getAdminGrantedYn(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public int updatePointTrade(PointTrade entity) {
        entity.onBeforeUpdate();
        queryLog.debug(
                "UPDATE POINT_TRADE pointKey={}, remainAmount={}",
                entity.getPointKey(),
                entity.getRemainAmount());
        return jdbcTemplate.update(
                """
                UPDATE POINT_TRADE SET
                    user_id = ?,
                    trade_type = ?,
                    amount = ?,
                    remain_amount = ?,
                    order_no = ?,
                    original_point_key = ?,
                    expire_yn = ?,
                    expire_ymd = ?,
                    status = ?,
                    admin_granted_yn = ?,
                    updated_at = ?
                WHERE point_key = ?
                """,
                entity.getUserId(),
                entity.getTradeType().name(),
                entity.getAmount(),
                entity.getRemainAmount(),
                entity.getOrderNo(),
                entity.getOriginalPointKey(),
                entity.getExpireYn().name(),
                entity.getExpireYmd(),
                entity.getStatus().getCode(),
                entity.getAdminGrantedYn(),
                entity.getUpdatedAt(),
                entity.getPointKey());
    }

    // remain·status 동시 갱신
    public int updateRemainAmountAndStatus(String pointKey, long remainAmount, PointStatus status) {
        LocalDateTime now = LocalDateTime.now();
        queryLog.debug(
                "UPDATE POINT_TRADE remain+status pointKey={}, remainAmount={}, status={}",
                pointKey,
                remainAmount,
                status.getCode());
        return jdbcTemplate.update(
                """
                UPDATE POINT_TRADE SET
                    remain_amount = ?,
                    status = ?,
                    updated_at = ?
                WHERE point_key = ?
                """,
                remainAmount,
                status.getCode(),
                now,
                pointKey);
    }

    public int updateTradeStatus(String pointKey, PointStatus status) {
        LocalDateTime now = LocalDateTime.now();
        queryLog.debug("UPDATE POINT_TRADE status pointKey={}, status={}", pointKey, status.getCode());
        return jdbcTemplate.update(
                """
                UPDATE POINT_TRADE SET
                    status = ?,
                    updated_at = ?
                WHERE point_key = ?
                """,
                status.getCode(),
                now,
                pointKey);
    }

    public int updateRemainAmount(PointTrade entity) {
        entity.onBeforeUpdate();
        queryLog.debug(
                "UPDATE POINT_TRADE remain_amount pointKey={}, remainAmount={}",
                entity.getPointKey(),
                entity.getRemainAmount());
        return jdbcTemplate.update(
                """
                UPDATE POINT_TRADE SET
                    remain_amount = ?,
                    updated_at = ?
                WHERE point_key = ?
                """,
                entity.getRemainAmount(),
                entity.getUpdatedAt(),
                entity.getPointKey());
    }

    public List<PointTrade> selectAvailableEarnTradesForUse(String userId) {
        queryLog.debug("SELECT POINT_TRADE available_for_use userId={}", userId);
        return jdbcTemplate.query(
                """
                SELECT
                    point_key,
                    user_id,
                    trade_type,
                    amount,
                    remain_amount,
                    order_no,
                    original_point_key,
                    expire_yn,
                    expire_ymd,
                    status,
                    request_id,
                    admin_granted_yn,
                    created_at,
                    updated_at
                FROM POINT_TRADE
                WHERE user_id = ?
                  AND trade_type IN ('PO01', 'PO05')
                  AND remain_amount > 0
                  AND status = ?
                ORDER BY
                    CASE WHEN admin_granted_yn = 'Y' THEN 0 ELSE 1 END,
                    expire_ymd ASC NULLS LAST,
                    created_at ASC
                """,
                ROW_MAPPER,
                userId,
                PointStatus.PS01.getCode());
    }

    public Optional<PointTrade> selectPointTradeByPointKey(String pointKey) {
        queryLog.debug("SELECT POINT_TRADE BY pointKey={}", pointKey);
        List<PointTrade> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE point_key = ?",
                ROW_MAPPER,
                pointKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    //request_id
    public Optional<PointTrade> selectPointTradeByRequestId(String requestId) {
        queryLog.debug("SELECT POINT_TRADE BY requestId={}", requestId);
        List<PointTrade> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE request_id = ?",
                ROW_MAPPER,
                requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<PointTrade> selectPointTradesByUserId(String userId) {
        queryLog.debug("SELECT POINT_TRADE BY userId={}", userId);
        return jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE user_id = ? ORDER BY created_at",
                ROW_MAPPER,
                userId);
    }

    // 원 PO02 기준 PO04 취소액 합계
    public long sumPo04AmountByOriginalPointKey(String originalPointKey) {
        queryLog.debug("SUM PO04 amount originalPointKey={}", originalPointKey);
        Long v = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                FROM POINT_TRADE
                WHERE trade_type = ?
                  AND original_point_key = ?
                """,
                Long.class,
                TradeType.PO04.name(),
                originalPointKey);
        return v != null ? v : 0L;
    }
}
