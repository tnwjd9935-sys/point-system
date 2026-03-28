package com.soojung.point.service;

import com.soojung.point.repository.PointTradeRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

// point_key 5자리 발급. DB에 없을 때까지 몇 번 재시도
@Component
public class PointKeyGenerator {

    private static final char[] ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // 헷갈리는 0/O 등 제외
    private static final int LENGTH = 5;

    private final SecureRandom random = new SecureRandom(); // 키 예측 어렵게
    private final PointTradeRepository pointTradeRepository; // 중복 여부만 조회

    public PointKeyGenerator(PointTradeRepository pointTradeRepository) {
        this.pointTradeRepository = pointTradeRepository;
    }

    public String nextUniquePointKey() {
        for (int attempt = 0; attempt < 32; attempt++) {
            String key = randomKey();
            if (pointTradeRepository.selectPointTradeByPointKey(key).isEmpty()) {
                return key;
            }
        }
        throw new IllegalStateException("point_key 생성에 실패했습니다.");
    }

    private String randomKey() {
        char[] buf = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            buf[i] = ALPHANUM[random.nextInt(ALPHANUM.length)];
        }
        return new String(buf);
    }
}
