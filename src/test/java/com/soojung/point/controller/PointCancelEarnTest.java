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
class PointCancelEarnTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void cancel_earn_ok_unused_po01() throws Exception {
        String userId = "6161616161";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 350, "requestId": "earn-ce-1"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availablePoint").value(350));

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "cancel-earn-1",
                                  "originRequestId": "earn-ce-1"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeType").value("PO03"))
                .andExpect(jsonPath("$.canceledAmount").value(350))
                .andExpect(jsonPath("$.availablePoint").value(0))
                .andExpect(jsonPath("$.originRequestId").value("earn-ce-1"));

        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM POINT_TRADE WHERE request_id = ?", String.class, "earn-ce-1");
        assertThat(status).isEqualTo("PS03");
    }

    @Test
    void cancel_earn_same_request_id_idempotent() throws Exception {
        String userId = "6262626262";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 100, "requestId": "earn-ce-2"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        String body =
                """
                {
                  "userId": "%s",
                  "requestId": "cancel-idem-earn",
                  "originRequestId": "earn-ce-2"
                }
                """
                        .formatted(userId);

        MvcResult first = mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n1 = objectMapper.readTree(first.getResponse().getContentAsString());
        String pk = n1.get("pointKey").asText();
        long ap = n1.get("availablePoint").asLong();

        MvcResult second = mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n2 = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(n2.get("pointKey").asText()).isEqualTo(pk);
        assertThat(n2.get("availablePoint").asLong()).isEqualTo(ap);
    }

    @Test
    void cancel_earn_fails_when_origin_missing() throws Exception {
        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "6363636363",
                                  "requestId": "ce-no-origin",
                                  "originRequestId": "no-earn"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("원 적립 거래를 찾을 수 없습니다."));
    }

    @Test
    void cancel_earn_fails_when_partially_used() throws Exception {
        String userId = "6464646464";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 500, "requestId": "earn-partial"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "orderNo": "ORD-P",
                                  "amount": 1,
                                  "requestId": "use-partial"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "ce-partial",
                                  "originRequestId": "earn-partial"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("일부 사용된 적립은 적립취소할 수 없습니다."));
    }

    @Test
    void cancel_earn_fails_when_user_mismatch() throws Exception {
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "6565656565", "amount": 50, "requestId": "earn-um"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "6565656566",
                                  "requestId": "ce-um",
                                  "originRequestId": "earn-um"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("userId가 원 적립 거래와 일치하지 않습니다."));
    }

    @Test
    void cancel_earn_fails_when_request_id_conflict() throws Exception {
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "6666666666", "amount": 10, "requestId": "earn-conflict-ce"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "6666666666",
                                  "requestId": "earn-conflict-ce",
                                  "originRequestId": "earn-conflict-ce"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestId가 이미 다른 유형의 거래에 사용 중입니다."));
    }

    @Test
    void cancel_earn_fails_second_cancel_same_origin() throws Exception {
        String userId = "6767676767";
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"userId": "%s", "amount": 80, "requestId": "earn-twice"}
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "ce-first",
                                  "originRequestId": "earn-twice"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/points/earn/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "requestId": "ce-second",
                                  "originRequestId": "earn-twice"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("취소할 수 없는 적립 상태입니다."));
    }
}
