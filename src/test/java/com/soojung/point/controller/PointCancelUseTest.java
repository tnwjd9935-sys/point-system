package com.soojung.point.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PointCancelUseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void cancel_ok_restores_balance_and_remain() throws Exception {
        String userId = "4444444444";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 1000,
                                  "requestId": "earn-cancel-1"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-C1",
                                  "amount": 400,
                                  "requestId": "use-for-cancel-1"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availablePoint").value(600));

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-req-1",
                                  "originRequestId": "use-for-cancel-1",
                                  "amount": 400
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeType").value("PO04"))
                .andExpect(jsonPath("$.canceledAmount").value(400))
                .andExpect(jsonPath("$.availablePoint").value(1000))
                .andExpect(jsonPath("$.originRequestId").value("use-for-cancel-1"));
    }

    @Test
    void cancel_same_request_id_returns_same_cancel_point_key() throws Exception {
        String userId = "5555555555";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 500,
                                  "requestId": "earn-cancel-2"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-C2",
                                  "amount": 200,
                                  "requestId": "use-for-cancel-2"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        String cancelBody =
                """
                {
                  "userId": "%s",
                  "requestId": "cancel-idem-1",
                  "originRequestId": "use-for-cancel-2",
                  "amount": 200
                }
                """
                        .formatted(userId);

        MvcResult first = mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n1 = objectMapper.readTree(first.getResponse().getContentAsString());
        String pk = n1.get("pointKey").asText();
        long ap1 = n1.get("availablePoint").asLong();

        MvcResult second = mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n2 = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(n2.get("pointKey").asText()).isEqualTo(pk);
        assertThat(n2.get("availablePoint").asLong()).isEqualTo(ap1);
    }

    @Test
    void cancel_fails_when_origin_missing() throws Exception {
        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "6666666666",
                                  "requestId": "cancel-no-origin",
                                  "originRequestId": "no-such-use",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("원 사용 거래를 찾을 수 없습니다."));
    }

    @Test
    void cancel_fails_when_origin_not_po02() throws Exception {
        String userId = "7777777777";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 300,
                                  "requestId": "earn-as-origin"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-wrong-type",
                                  "originRequestId": "earn-as-origin",
                                  "amount": 1
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("원 거래가 사용(PO02)이 아닙니다."));
    }

    @Test
    void cancel_fails_when_user_mismatch() throws Exception {
        String userId = "8888888888";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 400,
                                  "requestId": "earn-cancel-user"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-CU",
                                  "amount": 100,
                                  "requestId": "use-cancel-user"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "8888888889",
                                  "requestId": "cancel-bad-user",
                                  "originRequestId": "use-cancel-user",
                                  "amount": 100
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("userId가 원 사용 거래와 일치하지 않습니다."));
    }

    @Test
    void cancel_fails_second_time_with_new_cancel_request_id() throws Exception {
        String userId = "1212121212";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 600,
                                  "requestId": "earn-double-cancel"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-DC",
                                  "amount": 150,
                                  "requestId": "use-double-cancel"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-first",
                                  "originRequestId": "use-double-cancel",
                                  "amount": 150
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-second",
                                  "originRequestId": "use-double-cancel",
                                  "amount": 1
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 전액 취소된 사용 거래입니다."));
    }

    @Test
    void cancel_fails_when_request_id_used_by_other_trade_type() throws Exception {
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "1313131313",
                                  "amount": 100,
                                  "requestId": "earn-conflict-req"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "1313131313",
                                  "requestId": "earn-conflict-req",
                                  "originRequestId": "anything",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestId가 이미 다른 유형의 거래에 사용 중입니다."));
    }

    @Test
    void cancel_fails_when_point_detail_sum_not_equals_po02_amount() throws Exception {
        String userId = "1414141414";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 800,
                                  "requestId": "earn-detail-mismatch"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        MvcResult useResult = mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-MIS",
                                  "amount": 250,
                                  "requestId": "use-detail-mismatch"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();

        String usePointKey =
                objectMapper.readTree(useResult.getResponse().getContentAsString()).get("pointKey").asText();
        jdbcTemplate.update("UPDATE POINT_DETAIL SET amount = amount - 1 WHERE point_key = ?", usePointKey);

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-detail-mismatch",
                                  "originRequestId": "use-detail-mismatch",
                                  "amount": 250
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사용 상세와 원 거래 금액이 일치하지 않습니다. 운영 확인이 필요합니다."));
    }

    @Test
    void partial_cancel_1100_after_use_1200_restores_fifo_across_two_sources() throws Exception {
        String userId = "2020202020";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 1000, "requestId": "earn-pc-a"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 200, "requestId": "earn-pc-b"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        MvcResult useRes = mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-PC",
                                  "amount": 1200,
                                  "requestId": "use-partial-1200"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();
        String usePk =
                objectMapper.readTree(useRes.getResponse().getContentAsString()).get("pointKey").asText();

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-pc-1100",
                                  "originRequestId": "use-partial-1200",
                                  "amount": 1100
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceledAmount").value(1100));

        Long remain =
                jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, usePk);
        assertThat(remain).isEqualTo(100L);
    }

    @Test
    void partial_cancel_reduces_po02_remain_amount() throws Exception {
        String userId = "2121212121";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 500, "requestId": "earn-rem"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());
        MvcResult useRes = mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-RM",
                                  "amount": 500,
                                  "requestId": "use-rem-500"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();
        String usePk =
                objectMapper.readTree(useRes.getResponse().getContentAsString()).get("pointKey").asText();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, usePk))
                .isEqualTo(500L);

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-rem-200",
                                  "originRequestId": "use-rem-500",
                                  "amount": 200
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, usePk))
                .isEqualTo(300L);
    }

    @Test
    void repeated_partial_cancel_cannot_exceed_remaining_cancelable_amount() throws Exception {
        String userId = "2222222220";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 800, "requestId": "earn-rpt"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-RPT",
                                  "amount": 800,
                                  "requestId": "use-rpt-800"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-rpt-1",
                                  "originRequestId": "use-rpt-800",
                                  "amount": 500
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-rpt-2",
                                  "originRequestId": "use-rpt-800",
                                  "amount": 400
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 취소 가능 금액보다 큰 금액을 취소할 수 없습니다."));
    }

    @Test
    void partial_cancel_restores_non_expired_earn_remain() throws Exception {
        String userId = "2323232323";
        MvcResult earnRes = mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 300, "requestId": "earn-nexp"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();
        String earnPk = objectMapper.readTree(earnRes.getResponse().getContentAsString()).get("pointKey").asText();

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-NX",
                                  "amount": 300,
                                  "requestId": "use-nexp"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, earnPk))
                .isEqualTo(0L);

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-nexp",
                                  "originRequestId": "use-nexp",
                                  "amount": 300
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, earnPk))
                .isEqualTo(300L);
    }

    @Test
    void partial_cancel_expired_source_creates_po05() throws Exception {
        String userId = "2424242424";
        MvcResult earnRes = mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 400, "requestId": "earn-exp"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andReturn();
        String earnPk = objectMapper.readTree(earnRes.getResponse().getContentAsString()).get("pointKey").asText();

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-EXP",
                                  "amount": 400,
                                  "requestId": "use-exp"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE POINT_TRADE SET expire_ymd = '20200101' WHERE point_key = ?", earnPk);

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-exp",
                                  "originRequestId": "use-exp",
                                  "amount": 400
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        Integer po05 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM POINT_TRADE WHERE user_id = ? AND trade_type = 'PO05'", Integer.class, userId);
        assertThat(po05).isEqualTo(1);
        String po05ReqId = jdbcTemplate.queryForObject(
                "SELECT request_id FROM POINT_TRADE WHERE user_id = ? AND trade_type = 'PO05'",
                String.class,
                userId);
        assertThat(po05ReqId).startsWith("SYS-").contains("-P05-");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT remain_amount FROM POINT_TRADE WHERE point_key = ?", Long.class, earnPk))
                .isEqualTo(0L);
    }

    @Test
    void cancel_fails_when_amount_exceeds_remaining_cancelable() throws Exception {
        String userId = "2525252525";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 100, "requestId": "earn-bigc"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-BC",
                                  "amount": 100,
                                  "requestId": "use-bigc"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-bigc",
                                  "originRequestId": "use-bigc",
                                  "amount": 101
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("남은 취소 가능 금액보다 큰 금액을 취소할 수 없습니다."));
    }
}
