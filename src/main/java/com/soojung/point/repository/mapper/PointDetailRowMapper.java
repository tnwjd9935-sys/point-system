package com.soojung.point.repository.mapper;

import com.soojung.point.domain.entity.PointDetail;
import com.soojung.point.domain.enums.TradeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

public class PointDetailRowMapper implements RowMapper<PointDetail> {

    @Override
    public PointDetail mapRow(ResultSet rs, int rowNum) throws SQLException {
        PointDetail row = new PointDetail();
        row.setDetailId(rs.getLong("detail_id"));
        row.setUserId(rs.getString("user_id"));
        row.setPointKey(rs.getString("point_key"));
        row.setTradeType(TradeType.valueOf(rs.getString("trade_type")));
        row.setSourcePointKey(rs.getString("source_point_key"));
        row.setTargetPointKey(rs.getString("target_point_key"));
        row.setAmount(rs.getLong("amount"));
        row.setOrderNo(rs.getString("order_no"));
        row.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return row;
    }
}
