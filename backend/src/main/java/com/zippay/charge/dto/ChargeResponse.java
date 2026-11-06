package com.zippay.charge.dto;

import com.zippay.ledger.domain.LedgerEntry;
import com.zippay.ledger.domain.LedgerTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 충전 결과. 원장 거래에서만 만든다 — 첫 요청과 재요청이 같은 경로로 응답을 만들어 내용이 항상 같다.
 * balanceAfter는 "그 거래 직후" 잔액(분개의 balance_after)이다. 재요청 시점의 현재 잔액이 아니다.
 * 금액은 WalletResponse와 같은 이유로 문자열, 통화 자릿수로 표시한다.
 */
public record ChargeResponse(Long transactionId,
                             Long walletId,
                             String currency,
                             String amount,
                             String balanceAfter,
                             Instant createdAt) {

    public static ChargeResponse from(LedgerTransaction tx) {
        LedgerEntry walletEntry = tx.getEntries().stream()
                .filter(LedgerEntry::isWallet)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("충전 거래에 지갑 분개가 없습니다: " + tx.getId()));
        int scale = tx.getCurrency().fractionDigits();
        return new ChargeResponse(
                tx.getId(),
                walletEntry.getWalletId(),
                tx.getCurrency().name(),
                format(walletEntry.getAmount(), scale),
                format(walletEntry.getBalanceAfter(), scale),
                tx.getCreatedAt());
    }

    // UNNECESSARY: 자릿수 불변식이 깨져 있으면 반올림으로 숨기지 않고 예외로 드러낸다 (WalletResponse와 같음)
    private static String format(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }
}
