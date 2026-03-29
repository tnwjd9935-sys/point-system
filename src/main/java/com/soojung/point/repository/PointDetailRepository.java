package com.soojung.point.repository;

import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.enums.TradeType;
import com.soojung.point.repository.mapper.PointDetailRowMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

// POINT_DETAIL 테이블 전용 JdbcTemplate 접근
@Repository
public class PointDetailRepository {

    private static final Logger queryLog = LoggerFactory.getLogger("QUERY_LOG");

    private final JdbcTemplate jdbcTemplate;
    private static final PointDetailRowMapper ROW_MAPPER = new PointDetailRowMapper();

    public PointDetailRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PointDetail insertPointDetail(PointDetail entity) {
        if (entity.getCreatedAt() == null) {
            entity.onBeforeInsert();
        }
        queryLog.debug(
                "INSERT POINT_DETAIL userId={}, pointKey={}, tradeType={}, amount={}, orderNo={}",
                entity.getUserId(),
                entity.getPointKey(),
                entity.getTradeType(),
                entity.getAmount(),
                entity.getOrderNo());
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            """
                            INSERT INTO POINT_DETAIL (
                                user_id,
                                point_key,
                                trade_type,
                                source_point_key,
                                target_point_key,
                                amount,
                                order_no,
                                created_at
                            )
                            VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?
                            )
                            """,
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, entity.getUserId());
                    ps.setString(2, entity.getPointKey());
                    ps.setString(3, entity.getTradeType().name());
                    ps.setString(4, entity.getSourcePointKey());
                    ps.setString(5, entity.getTargetPointKey());
                    ps.setLong(6, entity.getAmount());
                    ps.setString(7, entity.getOrderNo());
                    ps.setObject(8, entity.getCreatedAt());
                    return ps;
                },
                keyHolder);
        Number key = Objects.requireNonNull(keyHolder.getKey());
        entity.setDetailId(key.longValue());
        return entity;
    }

    public int updatePointDetail(PointDetail entity) {
        queryLog.debug("UPDATE POINT_DETAIL detailId={}, pointKey={}", entity.getDetailId(), entity.getPointKey());
        return jdbcTemplate.update(
                """
                UPDATE POINT_DETAIL SET
                    user_id = ?,
                    point_key = ?,
                    trade_type = ?,
                    source_point_key = ?,
                    target_point_key = ?,
                    amount = ?,
                    order_no = ?
                WHERE detail_id = ?
                """,
                entity.getUserId(),
                entity.getPointKey(),
                entity.getTradeType().name(),
                entity.getSourcePointKey(),
                entity.getTargetPointKey(),
                entity.getAmount(),
                entity.getOrderNo(),
                entity.getDetailId());
    }

    public Optional<PointDetail> selectPointDetailByDetailId(Long detailId) {
        queryLog.debug("SELECT POINT_DETAIL BY detailId={}", detailId);
        List<PointDetail> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE detail_id = ?",
                ROW_MAPPER,
                detailId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<PointDetail> selectPointDetailsByPointKey(String pointKey) {
        queryLog.debug("SELECT POINT_DETAIL BY pointKey={}", pointKey);
        return jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE point_key = ? ORDER BY detail_id",
                ROW_MAPPER,
                pointKey);
    }

    // PO02 사용 건의 차감 상세
    public List<PointDetail> selectDetailsForUse(String usePointKey) {
        queryLog.debug("SELECT POINT_DETAIL usePointKey={}, tradeType=PO02", usePointKey);
        return jdbcTemplate.query(
                """
                SELECT *
                FROM POINT_DETAIL
                WHERE point_key = ?
                  AND trade_type = ?
                ORDER BY detail_id
                """,
                ROW_MAPPER,
                usePointKey,
                TradeType.PO02.name());
    }

    public List<PointDetail> selectPointDetailsByUserId(String userId) {
        queryLog.debug("SELECT POINT_DETAIL BY userId={}", userId);
        return jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE user_id = ? ORDER BY detail_id",
                ROW_MAPPER,
                userId);
    }
}
