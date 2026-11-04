package com.zippay.ledger.exception;

/** 원장 거래의 구조가 규칙에 맞지 않을 때 (분개 합 ≠ 0, 유형별 분개 구성 위반 등). 내부 로직 버그를 뜻한다. */
public class InvalidLedgerTransactionException extends RuntimeException {
    public InvalidLedgerTransactionException(String message) {
        super(message);
    }
}
