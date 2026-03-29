package com.soojung.point.domain.enums;

import java.util.Locale;

// POINT_TRADE.status 저장 코드 (VARCHAR)
public enum PointStatus {
    PS01("PS01"), // 유효
    PS02("PS02"), // 만료
    PS03("PS03"), // 취소(무효)
    PS04("PS04"); // 사용취소됨

    private final String code;

    PointStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    // DB/외부 문자열 → enum. PS01~PS04 및 ACTIVE, EXPIRED, CANCELED/CANCELLED(대소문자 무시)
    public static PointStatus fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("거래 상태 값이 비어 있습니다. POINT_TRADE.status 컬럼을 확인하세요.");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "PS01", "ACTIVE" -> PS01;
            case "PS02", "EXPIRED" -> PS02;
            case "PS03", "CANCELED", "CANCELLED" -> PS03;
            case "PS04" -> PS04;
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 거래 상태 값입니다. 입력=["
                            + raw
                            + "], 허용=PS01~PS04 또는 ACTIVE/EXPIRED/CANCELED/CANCELLED(대소문자 무시)");
        };
    }
}
