package com.soojung.point.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// 동일 사용자 동시 use 시 잔고가 과도하게 깎이지 않는지 확인 (다중 스레드 스모크)
@SpringBootTest
@AutoConfigureMockMvc
class PointConcurrentUseTest {

    private static final int THREADS = 20;
    private static final long EARN_AMOUNT = 10_000L;
    private static final long EACH_USE = 1_000L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void concurrent_use_requests_should_not_overdraw_balance() throws Exception {
        String userId = "9090909090";

        mockMvc.perform(post("/api/points/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "userId": "%s",
                                  "amount": 10000,
                                  "requestId": "earn-concurrent-use"
                                }
                                """
                                        .formatted(userId)))
                .andExpect(status().isOk());

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        MvcResult r = mockMvc.perform(post("/api/points/use")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "userId": "%s",
                                                  "orderNo": "ORD-CNC-%d",
                                                  "amount": 1000,
                                                  "requestId": "use-concurrent-%d"
                                                }
                                                """
                                                        .formatted(userId, n, n)))
                                .andReturn();
                        if (r.getResponse().getStatus() == 200) {
                            success.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(doneGate.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get() + failed.get()).isEqualTo(THREADS);
        assertThat(success.get()).isLessThanOrEqualTo(10);
        assertThat((long) success.get() * EACH_USE).isLessThanOrEqualTo(EARN_AMOUNT);

        Long balance = jdbcTemplate.queryForObject(
                "SELECT available_point FROM USER_BALANCE WHERE user_id = ?", Long.class, userId);
        assertThat(balance).isNotNull();
        assertThat(balance).isGreaterThanOrEqualTo(0L);
        assertThat(balance).isEqualTo(EARN_AMOUNT - (long) success.get() * EACH_USE);
    }
}
