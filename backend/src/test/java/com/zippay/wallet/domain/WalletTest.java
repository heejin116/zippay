package com.zippay.wallet.domain;

import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.wallet.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    void new_wallet_has_zero_balance() {
        Wallet wallet = Wallet.create(1L, Currency.KRW);

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void increase_and_decrease_change_balance() {
        Wallet wallet = Wallet.create(1L, Currency.KRW);

        wallet.increase(new BigDecimal("10000"));
        wallet.decrease(new BigDecimal("3000"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("7000");
    }

    @Test
    void decrease_can_reach_exactly_zero() {
        Wallet wallet = Wallet.create(1L, Currency.KRW);
        wallet.increase(new BigDecimal("5000"));

        wallet.decrease(new BigDecimal("5000"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void decrease_below_zero_is_rejected_and_balance_is_unchanged() {
        Wallet wallet = Wallet.create(1L, Currency.KRW);
        wallet.increase(new BigDecimal("5000"));

        assertThatThrownBy(() -> wallet.decrease(new BigDecimal("5001")))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("5000");
    }

    @Test
    void non_positive_or_null_amount_is_rejected() {
        Wallet wallet = Wallet.create(1L, Currency.KRW);

        assertThatThrownBy(() -> wallet.increase(BigDecimal.ZERO)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> wallet.increase(new BigDecimal("-1"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> wallet.decrease(null)).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void amount_exceeding_currency_scale_is_rejected() {
        Wallet krw = Wallet.create(1L, Currency.KRW);
        Wallet usd = Wallet.create(1L, Currency.USD);

        assertThatThrownBy(() -> krw.increase(new BigDecimal("100.5"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> usd.increase(new BigDecimal("1.001"))).isInstanceOf(InvalidAmountException.class);

        usd.increase(new BigDecimal("1.25"));
        assertThat(usd.getBalance()).isEqualByComparingTo("1.25");
    }
}
