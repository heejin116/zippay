package com.zippay.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyTest {

    @Test
    void from_accepts_supported_uppercase_codes() {
        assertThat(Currency.from("KRW")).isEqualTo(Currency.KRW);
        assertThat(Currency.from("USD")).isEqualTo(Currency.USD);
        assertThat(Currency.from("JPY")).isEqualTo(Currency.JPY);
    }

    @Test
    void from_rejects_unsupported_lowercase_and_null() {
        assertThatThrownBy(() -> Currency.from("EUR")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> Currency.from("krw")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> Currency.from(null)).isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void scale_is_checked_per_currency() {
        assertThatCode(() -> Currency.KRW.requireValidScale(new BigDecimal("1000"))).doesNotThrowAnyException();
        assertThatCode(() -> Currency.KRW.requireValidScale(new BigDecimal("1000.00"))).doesNotThrowAnyException(); // 뒤쪽 0은 허용
        assertThatCode(() -> Currency.USD.requireValidScale(new BigDecimal("12.34"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> Currency.KRW.requireValidScale(new BigDecimal("0.5"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Currency.JPY.requireValidScale(new BigDecimal("1.5"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Currency.USD.requireValidScale(new BigDecimal("0.001"))).isInstanceOf(InvalidAmountException.class);
    }
}
