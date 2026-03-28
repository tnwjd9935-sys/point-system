package com.soojung.point.repository.mapper;

import com.soojung.point.domain.entity.PointTrade;
import com.soojung.point.domain.enums.ExpireYn;
import com.soojung.point.domain.enums.PointStatus;
import com.soojung.point.domain.enums.TradeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

// POINT_TRADE 한 행 → PointTrade (enum은 문자열로 저장됨)
public class PointTradeRowMapper implements RowMapper<PointTrade> {

    @Override
    public PointTrade mapRow(ResultSet rs, int rowNum) throws SQLException {
        PointTrade row = new PointTrade();
        row.setPointKey(rs.getString("point_key"));
        row.setUserId(rs.getString("user_id"));
        row.setTradeType(TradeType.valueOf(rs.getString("trade_type")));
        row.setAmount(rs.getLong("amount"));
        row.setRemainAmount(rs.getLong("remain_amount"));
        row.setOrderNo(rs.getString("order_no"));
        row.setOriginalPointKey(rs.getString("original_point_key"));
        row.setExpireYn(ExpireYn.valueOf(rs.getString("expire_yn")));
        row.setExpireYmd(rs.getString("expire_ymd"));
        row.setStatus(PointStatus.valueOf(rs.getString("status")));
        row.setRequestId(rs.getString("request_id"));
        row.setAdminGrantedYn(rs.getString("admin_granted_yn"));
        row.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        row.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return row;
    }
}
