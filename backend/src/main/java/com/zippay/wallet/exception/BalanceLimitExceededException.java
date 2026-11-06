package com.zippay.wallet.exception;

/** 입금 후 잔액이 지갑 보유 한도를 넘음 → 422 (요청 자체는 올바르지만 현재 잔액 상태 때문에 처리 불가) */
public class BalanceLimitExceededException extends RuntimeException {
    public BalanceLimitExceededException(String message) {
        super(message);
    }
}
