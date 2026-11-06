package com.zippay.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 멱등성 판정용 요청 해시: 정규화한 요청 문자열의 SHA-256 소문자 hex (V3 ck_ledger_tx_request_hash 형식).
 * 원본 JSON 바이트가 아니라 파싱·정규화한 값으로 만든다 — 공백·필드 순서·"10000"/"10000.00" 같은
 * 표기 차이로 정상 재시도가 422가 되지 않게 하기 위해서다.
 * 정규화 문자열에는 거래 유형을 반드시 넣는다 (같은 키를 충전→송금에 재사용하면 422가 나도록).
 */
public final class RequestHash {

    private RequestHash() {}

    public static String sha256Hex(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);   // 소문자 hex 64자
        } catch (NoSuchAlgorithmException e) {
            // 모든 JVM 구현은 SHA-256을 제공해야 한다 (MessageDigest 명세)
            throw new IllegalStateException(e);
        }
    }
}
