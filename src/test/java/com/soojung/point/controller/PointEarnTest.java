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
class PointEarnTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void earn_ok() throws Exception {
        String body = """
                {
                  "userId": "1234567890",
                  "amount": 1000,
                  "requestId": "earn-req-1"
                }
                """;
        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointKey").exists())
                .andExpect(jsonPath("$.availablePoint").value(1000))
                .andExpect(jsonPath("$.tradeType").value("PO01"));
    }

    @Test
    void earn_same_request_id_idempotent() throws Exception {
        String body = """
                {
                  "userId": "9999999999",
                  "amount": 500,
                  "requestId": "idempotent-req-1"
                }
                """;
        MvcResult first = mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n1 = objectMapper.readTree(first.getResponse().getContentAsString());
        String pointKey = n1.get("pointKey").asText();
        long ap1 = n1.get("availablePoint").asLong();

        MvcResult second = mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode n2 = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(n2.get("pointKey").asText()).isEqualTo(pointKey);
        assertThat(n2.get("availablePoint").asLong()).isEqualTo(ap1);
    }
}
