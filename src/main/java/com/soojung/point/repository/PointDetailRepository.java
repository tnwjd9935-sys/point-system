package com.soojung.point.repository;

import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.repository.mapper.PointDetailRowMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PointDetailRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final PointDetailRowMapper ROW_MAPPER = new PointDetailRowMapper();

    public PointDetailRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PointDetail insertPointDetail(PointDetail entity) {
        if (entity.getCreatedAt() == null) {
            entity.onBeforeInsert();
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            """
                            INSERT INTO POINT_DETAIL (
                                user_id, point_key, trade_type,
                                source_point_key, target_point_key, amount, order_no, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
        List<PointDetail> rows = jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE detail_id = ?",
                ROW_MAPPER,
                detailId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<PointDetail> selectPointDetailsByPointKey(String pointKey) {
        return jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE point_key = ? ORDER BY detail_id",
                ROW_MAPPER,
                pointKey);
    }

    public List<PointDetail> selectPointDetailsByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM POINT_DETAIL WHERE user_id = ? ORDER BY detail_id",
                ROW_MAPPER,
                userId);
    }
}
