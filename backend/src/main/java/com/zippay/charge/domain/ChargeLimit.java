package com.zippay.charge.domain;

import com.zippay.charge.exception.ChargeLimitExceededException;
import com.zippay.common.money.Currency;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 1회 충전 한도 (정책 가정값). 충전 요청 하나에만 걸리는 규칙이라 충전 도메인에 둔다.
 * 보유 한도는 송금 입금에도 적용되므로 Wallet.increase()에 있다.
 * DB CHECK를 걸지 않는 이유는 보유 한도와 같다 (정책 값은 바뀔 수 있음).
 */
public final class ChargeLimit {

    private static final Map<Currency, BigDecimal> MAX_PER_CHARGE = new EnumMap<>(Map.of(
            Currency.KRW, new BigDecimal("1000000"),
            Currency.USD, new BigDecimal("1000.00"),
            Currency.JPY, new BigDecimal("100000")));

    private ChargeLimit() {}

    public static BigDecimal maxPerCharge(Currency currency) {
        return Objects.requireNonNull(MAX_PER_CHARGE.get(currency), "충전 한도 미정의 통화: " + currency);
    }

    public static void check(Currency currency, BigDecimal amount) {
        BigDecimal limit = maxPerCharge(currency);
        if (amount.compareTo(limit) > 0) {
            throw new ChargeLimitExceededException(
                    currency.name() + " 1회 충전 한도(" + limit.toPlainString() + ")를 초과합니다.");
        }
    }
}
