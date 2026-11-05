package com.zippay.ledger.service;

import com.zippay.ledger.domain.AccountType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * LedgerService.post()의 입력 한 줄: "어느 계정을 얼마나 움직일지"만 담는다.
 * balance_after·통화는 호출자가 넣지 않는다 — post()가 지갑을 락 잡은 뒤 계산해 LedgerEntry로 만든다.
 *
 * @param amount 부호 있는 금액 (+증가 / −감소)
 */
public record Posting(AccountType accountType, Long walletId, BigDecimal amount) {

    public Posting {
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(amount, "amount");
        if (accountType == AccountType.WALLET) {
            Objects.requireNonNull(walletId, "walletId");
        }
    }

    public static Posting wallet(Long walletId, BigDecimal amount) {
        return new Posting(AccountType.WALLET, walletId, amount);
    }

    public static Posting external(BigDecimal amount) {
        return new Posting(AccountType.EXTERNAL, null, amount);
    }

    public boolean isWallet() { return accountType == AccountType.WALLET; }
}
