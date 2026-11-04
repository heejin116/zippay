package com.zippay.ledger.domain;

/** 분개 계정 유형. 값 목록은 V3의 ck_ledger_entries_account_type과 같이 바꾼다. */
public enum AccountType {
    /** ZipPay 지갑. wallet_id·balance_after 필수 */
    WALLET,
    /** ZipPay 밖(은행) 상대계정. 잔액 캐시·락 없음 → wallet_id·balance_after 모두 NULL */
    EXTERNAL
}
