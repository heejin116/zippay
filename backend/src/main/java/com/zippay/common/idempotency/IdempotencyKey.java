package com.zippay.common.idempotency;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Idempotency-Key 헤더 값 검증·정규화.
 * 형식은 UUID로 고정한다 — 프론트가 crypto.randomUUID()로 만들고, 길이(36자)가 V3의 varchar(64) 안에 항상 들어온다.
 * 대소문자만 다른 키는 같은 키로 본다 (소문자로 정규화해 저장).
 */
public final class IdempotencyKey {

    public static final String HEADER = "Idempotency-Key";
    /** 재요청에 원래 결과를 돌려줄 때 붙이는 응답 헤더 */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private IdempotencyKey() {}

    public static String normalize(String raw) {
        if (raw == null || !UUID.matcher(raw).matches()) {
            throw new InvalidIdempotencyKeyException(HEADER + "는 UUID 형식이어야 합니다.");
        }
        return raw.toLowerCase(Locale.ROOT);
    }
}
