package com.zippay.common.idempotency;

/** 이미 쓴 Idempotency-Key로 다른 내용의 요청을 보냄 → 422 */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
