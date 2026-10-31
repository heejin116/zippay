package com.zippay.common.money;

import java.math.BigDecimal;

/**
 * 지원 통화와 통화별 소수 자릿수.
 * 값 목록은 V2 마이그레이션의 ck_wallets_currency / ck_wallets_balance_scale과 반드시 같이 바꾼다.
 */
public enum Currency {
    KRW(0),
    USD(2),
    JPY(0);

    private final int fractionDigits;

    Currency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    public int fractionDigits() { return fractionDigits; }

    /** API 입력 문자열 → 통화. ISO 4217 대문자 코드만 허용한다. */
    public static Currency from(String code) {
        if (code != null) {
            for (Currency c : values()) {
                if (c.name().equals(code)) {
                    return c;
                }
            }
        }
        throw new UnsupportedCurrencyException("지원하지 않는 통화입니다: " + code);
    }

    /** 통화의 소수 자릿수를 넘는 금액을 거부한다. 반올림해서 받아주지 않는다. (예: KRW 0.5, USD 0.001) */
    public void requireValidScale(BigDecimal amount) {
        // 1000.00 같은 뒤쪽 0은 허용해야 하므로 stripTrailingZeros 후 scale 비교
        if (amount.stripTrailingZeros().scale() > fractionDigits) {
            throw new InvalidAmountException(
                    name() + "는 소수 " + fractionDigits + "자리까지만 허용됩니다: " + amount.toPlainString());
        }
    }
}
