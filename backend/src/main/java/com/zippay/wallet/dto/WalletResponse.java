package com.zippay.wallet.dto;

import com.zippay.wallet.domain.Wallet;

import java.math.RoundingMode;

/**
 * balance는 문자열로 내린다 — JSON number는 클라이언트(JS)에서 double로 파싱돼 정밀도를 잃을 수 있다.
 * DB는 scale 4로 저장하므로 통화 자릿수로 맞춰 표시한다 (KRW "10000", USD "12.50").
 */
public record WalletResponse(Long id, String currency, String balance) {

    public static WalletResponse from(Wallet wallet) {
        // UNNECESSARY: 자릿수 불변식이 깨져 있으면 반올림으로 숨기지 않고 예외로 드러낸다
        String balance = wallet.getBalance()
                .setScale(wallet.getCurrency().fractionDigits(), RoundingMode.UNNECESSARY)
                .toPlainString();
        return new WalletResponse(wallet.getId(), wallet.getCurrency().name(), balance);
    }
}
