package com.zippay.charge.exception;

/** 1회 충전 한도 초과 → 400 (현재 상태와 무관하게 요청 값만으로 판정되는 오류) */
public class ChargeLimitExceededException extends RuntimeException {
    public ChargeLimitExceededException(String message) {
        super(message);
    }
}
