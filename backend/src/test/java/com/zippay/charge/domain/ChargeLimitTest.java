package com.zippay.charge.domain;

import com.zippay.charge.exception.ChargeLimitExceededException;
import com.zippay.common.money.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeLimitTest {

    @Test
    void every_currency_has_a_limit() {
        // 통화를 추가하고 한도를 빠뜨리면 여기서 걸린다
        for (Currency c : Currency.values()) {
            assertThatCode(() -> ChargeLimit.maxPerCharge(c)).doesNotThrowAnyException();
        }
    }

    @Test
    void amount_up_to_limit_is_allowed_and_over_limit_is_rejected() {
        assertThatCode(() -> ChargeLimit.check(Currency.KRW, new BigDecimal("1000000"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChargeLimit.check(Currency.KRW, new BigDecimal("1000001")))
                .isInstanceOf(ChargeLimitExceededException.class);
        assertThatThrownBy(() -> ChargeLimit.check(Currency.USD, new BigDecimal("1000.01")))
                .isInstanceOf(ChargeLimitExceededException.class);
    }
}
