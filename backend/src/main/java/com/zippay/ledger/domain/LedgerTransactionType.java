package com.zippay.ledger.domain;

/** 거래 유형. 값 목록은 V3의 ck_ledger_tx_type과 같이 바꾼다. */
public enum LedgerTransactionType {
    /** 외부(은행) → 내 지갑. 분개: WALLET(+) / EXTERNAL(−) */
    CHARGE,
    /** 내 지갑 → 상대 지갑 (P2P). 분개: WALLET(−) / WALLET(+) */
    TRANSFER
}
