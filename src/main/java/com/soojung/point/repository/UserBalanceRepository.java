package com.soojung.point.repository;

import com.soojung.point.domain.entity.UserBalance;
import com.soojung.point.repository.mapper.UserBalanceRowMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// USER_BALANCE 테이블 전용 JdbcTemplate 접근
@Repository
public class UserBalanceRepository {

    private final JdbcTemplate jdbcTemplate; // 스프링이 주입, 트랜잭션은 서비스에서
    private static final UserBalanceRowMapper ROW_MAPPER = new UserBalanceRowMapper(); // ResultSet → 엔티티

    public UserBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 신규 사용자 잔고 행
    public void insertUserBalance(UserBalance entity) {
        if (entity.getCreatedAt() == null) {
            entity.onBeforeInsert();
        }
        jdbcTemplate.update(
                """
                INSERT INTO USER_BALANCE (
                    user_id,
                    available_point,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, ?, ?, ?
                )
                """,
                entity.getUserId(),
                entity.getAvailablePoint(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    // 가용 포인트·updated_at 갱신
    public int updateAvailablePoint(UserBalance entity) {
        entity.onBeforeUpdate();
        return jdbcTemplate.update(
                """
                UPDATE USER_BALANCE
                SET available_point = ?, updated_at = ?
                WHERE user_id = ?
                """,
                entity.getAvailablePoint(),
                entity.getUpdatedAt(),
                entity.getUserId());
    }

    public Optional<UserBalance> selectUserBalance(String userId) {
        List<UserBalance> rows = jdbcTemplate.query(
                "SELECT * FROM USER_BALANCE WHERE user_id = ?",
                ROW_MAPPER,
                userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
