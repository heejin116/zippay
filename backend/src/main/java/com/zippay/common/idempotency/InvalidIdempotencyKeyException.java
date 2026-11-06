package com.zippay.common.idempotency;

/** Idempotency-Key 형식 오류 → 400. 클라이언트가 고칠 수 있는 오류라 5xx로 내지 않는다. */
public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
