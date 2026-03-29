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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class PointUseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void use_ok_after_earn() throws Exception {
        String earnBody = """
                {
                  "userId": "1111111111",
                  "amount": 1000,
                  "requestId": "earn-for-use-1"
                }
                """;
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(earnBody))
                .andExpect(status().isOk());

        String useBody = """
                {
                  "userId": "1111111111",
                  "orderNo": "ORD-001",
                  "amount": 300,
                  "requestId": "use-req-1"
                }
                """;
        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(useBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeType").value("PO02"))
                .andExpect(jsonPath("$.amount").value(300))
                .andExpect(jsonPath("$.availablePoint").value(700))
                .andExpect(jsonPath("$.orderNo").value("ORD-001"));
    }

    @Test
    void use_fails_when_insufficient_balance() throws Exception {
        String useBody = """
                {
                  "userId": "2222222222",
                  "orderNo": "ORD-X",
                  "amount": 100,
                  "requestId": "use-no-balance"
                }
                """;
        mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(useBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("가용 포인트가 부족합니다."));
    }

    @Test
    void use_same_request_id_does_not_double_deduct() throws Exception {
        String earnBody = """
                {
                  "userId": "3333333333",
                  "amount": 800,
                  "requestId": "earn-for-use-2"
                }
                """;
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(earnBody))
                .andExpect(status().isOk());

        String useBody = """
                {
                  "userId": "3333333333",
                  "orderNo": "ORD-002",
                  "amount": 200,
                  "requestId": "use-idem-1"
                }
                """;
        MvcResult first = mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(useBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n1 = objectMapper.readTree(first.getResponse().getContentAsString());
        String pointKey = n1.get("pointKey").asText();
        long ap1 = n1.get("availablePoint").asLong();

        MvcResult second = mockMvc.perform(post("/api/points/use")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(useBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n2 = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(n2.get("pointKey").asText()).isEqualTo(pointKey);
        assertThat(n2.get("availablePoint").asLong()).isEqualTo(ap1);
    }
}
