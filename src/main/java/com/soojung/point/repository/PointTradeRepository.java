package com.soojung.point.repository;

import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.repository.mapper.PointTradeRowMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// POINT_TRADE 테이블 전용 JdbcTemplate 접근
@Repository
public class PointTradeRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final PointTradeRowMapper ROW_MAPPER = new PointTradeRowMapper();

    public PointTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertPointTrade(PointTrade entity) {
        if (entity.getCreatedAt() == null) {
            entity.onBeforeInsert();
        }
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
                entity.getStatus().name(),
                entity.getRequestId(),
                entity.getAdminGrantedYn(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public int updatePointTrade(PointTrade entity) {
        entity.onBeforeUpdate();
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
                entity.getStatus().name(),
                entity.getAdminGrantedYn(),
                entity.getUpdatedAt(),
                entity.getPointKey());
    }

    public Optional<PointTrade> selectPointTradeByPointKey(String pointKey) {
        List<PointTrade> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE point_key = ?",
                ROW_MAPPER,
                pointKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    //request_id
    public Optional<PointTrade> selectPointTradeByRequestId(String requestId) {
        List<PointTrade> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE request_id = ?",
                ROW_MAPPER,
                requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<PointTrade> selectPointTradesByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM POINT_TRADE WHERE user_id = ? ORDER BY created_at",
                ROW_MAPPER,
                userId);
    }
}
