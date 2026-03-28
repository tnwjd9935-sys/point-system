package com.soojung.point.repository.mapper;

import com.soojung.point.domain.entity.UserBalance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

// USER_BALANCE 한 행 → UserBalance
public class UserBalanceRowMapper implements RowMapper<UserBalance> {

    @Override
    public UserBalance mapRow(ResultSet rs, int rowNum) throws SQLException {
        UserBalance row = new UserBalance();
        row.setUserId(rs.getString("user_id"));
        row.setAvailablePoint(rs.getLong("available_point"));
        row.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        row.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return row;
    }
}
