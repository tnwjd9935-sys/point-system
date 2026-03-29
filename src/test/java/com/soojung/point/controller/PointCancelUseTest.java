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
                                  "originRequestId": "use-for-cancel-1"
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
                  "originRequestId": "use-for-cancel-2"
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
                                  "originRequestId": "no-such-use"
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
                                  "originRequestId": "earn-as-origin"
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
                                  "originRequestId": "use-cancel-user"
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
                                  "originRequestId": "use-double-cancel"
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
                                  "originRequestId": "use-double-cancel"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 취소되었거나 취소할 수 없는 사용 거래입니다."));
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
                                  "originRequestId": "anything"
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
                                  "originRequestId": "use-detail-mismatch"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사용 상세와 원 거래 금액이 일치하지 않습니다. 운영 확인이 필요합니다."));
    }
}
