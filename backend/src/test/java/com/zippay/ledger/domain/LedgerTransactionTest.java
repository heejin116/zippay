package com.zippay.ledger.domain;

import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.zippay.common.money.Currency.KRW;
import static com.zippay.common.money.Currency.USD;
import static com.zippay.ledger.domain.LedgerTransactionType.CHARGE;
import static com.zippay.ledger.domain.LedgerTransactionType.TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    static final String HASH = "a".repeat(64);

    static BigDecimal d(String v) { return new BigDecimal(v); }

    static LedgerTransaction tx(LedgerTransactionType type, Currency currency, LedgerEntry... entries) {
        return LedgerTransaction.create(1L, "key-1", HASH, type, currency, List.of(entries));
    }

    // ---------- 정상 ----------

    @Test
    void charge_is_wallet_plus_and_external_minus() {
        LedgerTransaction tx = tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")),
                LedgerEntry.external(KRW, d("-10000")));

        assertThat(tx.getEntries()).hasSize(2);
        assertThat(tx.getEntries()).allSatisfy(e -> {
            assertThat(e.getTransaction()).isSameAs(tx);
            assertThat(e.getCurrency()).isEqualTo(KRW);
        });
    }

    @Test
    void charge_entry_order_does_not_matter() {
        LedgerTransaction tx = tx(CHARGE, KRW,
                LedgerEntry.external(KRW, d("-10000")),
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")));

        assertThat(tx.getEntries()).hasSize(2);
    }

    @Test
    void transfer_is_two_wallet_entries() {
        LedgerTransaction tx = tx(TRANSFER, KRW,
                LedgerEntry.wallet(10L, KRW, d("-3000"), d("7000")),
                LedgerEntry.wallet(20L, KRW, d("3000"), d("3000")));

        assertThat(tx.getEntries()).extracting(LedgerEntry::getWalletId).containsExactly(10L, 20L);
    }

    @Test
    void usd_allows_two_decimal_places_and_trailing_zeros() {
        tx(CHARGE, USD,
                LedgerEntry.wallet(10L, USD, d("12.50"), d("12.5000")),
                LedgerEntry.external(USD, d("-12.5")));
    }

    @Test
    void entries_cannot_be_modified_from_outside() {
        LedgerTransaction tx = tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")),
                LedgerEntry.external(KRW, d("-10000")));

        assertThatThrownBy(() -> tx.getEntries().add(LedgerEntry.external(KRW, d("-1"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- 분개 통화 = 거래 통화 ----------

    @Test
    void entry_currency_different_from_transaction_is_rejected() {
        assertThatThrownBy(() -> tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("10"), d("10")),
                LedgerEntry.external(USD, d("-10"))))
                .isInstanceOf(InvalidLedgerTransactionException.class)
                .hasMessageContaining("통화");
    }

    @Test
    void all_entries_in_other_currency_than_header_is_rejected() {
        // 분개끼리는 통화가 같아도 헤더와 다르면 거부
        assertThatThrownBy(() -> tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, USD, d("10"), d("10")),
                LedgerEntry.external(USD, d("-10"))))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    // ---------- 분개 합 = 0 ----------

    @Test
    void unbalanced_entries_are_rejected() {
        assertThatThrownBy(() -> tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")),
                LedgerEntry.external(KRW, d("-9999"))))
                .isInstanceOf(InvalidLedgerTransactionException.class)
                .hasMessageContaining("분개 합");
    }

    @Test
    void sum_zero_with_different_scales_is_accepted() {
        // 10000 + (-10000.0000) = 0.0000 → equals(ZERO)였다면 false라서 잘못 거부됐을 케이스
        tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")),
                LedgerEntry.external(KRW, d("-10000.0000")));
    }

    // ---------- 통화 소수 자릿수 (분개 생성 시점에 검증) ----------

    @Test
    void amount_exceeding_currency_scale_is_rejected() {
        assertThatThrownBy(() -> LedgerEntry.external(KRW, d("-0.5"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> LedgerEntry.external(USD, d("-1.001"))).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> LedgerEntry.wallet(10L, KRW, d("0.5"), d("1"))).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void balance_after_exceeding_currency_scale_is_rejected() {
        assertThatThrownBy(() -> LedgerEntry.wallet(10L, KRW, d("1"), d("10000.5")))
                .isInstanceOf(InvalidAmountException.class);
    }

    // ---------- 유형별 분개 구성 ----------

    @Test
    void charge_without_external_is_rejected() {
        assertThatThrownBy(() -> tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("-3000"), d("7000")),
                LedgerEntry.wallet(20L, KRW, d("3000"), d("3000"))))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void charge_that_decreases_wallet_is_rejected() {
        // 지갑(−)/외부(+)는 출금이지 충전이 아니다
        assertThatThrownBy(() -> tx(CHARGE, KRW,
                LedgerEntry.wallet(10L, KRW, d("-1000"), d("0")),
                LedgerEntry.external(KRW, d("1000"))))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void transfer_with_external_is_rejected() {
        assertThatThrownBy(() -> tx(TRANSFER, KRW,
                LedgerEntry.wallet(10L, KRW, d("10000"), d("10000")),
                LedgerEntry.external(KRW, d("-10000"))))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void transfer_to_same_wallet_is_rejected() {
        assertThatThrownBy(() -> tx(TRANSFER, KRW,
                LedgerEntry.wallet(10L, KRW, d("-1000"), d("0")),
                LedgerEntry.wallet(10L, KRW, d("1000"), d("1000"))))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void entry_count_other_than_two_is_rejected() {
        assertThatThrownBy(() -> tx(CHARGE, KRW))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void entry_cannot_belong_to_two_transactions() {
        LedgerEntry wallet = LedgerEntry.wallet(10L, KRW, d("10000"), d("10000"));
        LedgerEntry external = LedgerEntry.external(KRW, d("-10000"));
        tx(CHARGE, KRW, wallet, external);

        assertThatThrownBy(() -> tx(CHARGE, KRW, wallet, external))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    // ---------- 멱등성 컬럼 형식 ----------

    @Test
    void invalid_idempotency_key_is_rejected() {
        List<LedgerEntry> entries = List.of(
                LedgerEntry.wallet(10L, KRW, d("1"), d("1")), LedgerEntry.external(KRW, d("-1")));

        assertThatThrownBy(() -> LedgerTransaction.create(1L, " ", HASH, CHARGE, KRW, entries))
                .isInstanceOf(InvalidLedgerTransactionException.class);
        assertThatThrownBy(() -> LedgerTransaction.create(1L, "k".repeat(65), HASH, CHARGE, KRW, entries))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    @Test
    void invalid_request_hash_is_rejected() {
        List<LedgerEntry> entries = List.of(
                LedgerEntry.wallet(10L, KRW, d("1"), d("1")), LedgerEntry.external(KRW, d("-1")));

        assertThatThrownBy(() -> LedgerTransaction.create(1L, "k", "A".repeat(64), CHARGE, KRW, entries))
                .isInstanceOf(InvalidLedgerTransactionException.class);   // 대문자 거부
        assertThatThrownBy(() -> LedgerTransaction.create(1L, "k", "a".repeat(63), CHARGE, KRW, entries))
                .isInstanceOf(InvalidLedgerTransactionException.class);
    }

    // ---------- 분개 단위 ----------

    @Test
    void zero_amount_entry_is_rejected() {
        assertThatThrownBy(() -> LedgerEntry.external(KRW, BigDecimal.ZERO)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> LedgerEntry.wallet(10L, KRW, d("0.0000"), d("0"))).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void negative_balance_after_is_rejected() {
        assertThatThrownBy(() -> LedgerEntry.wallet(10L, KRW, d("-1"), d("-1"))).isInstanceOf(InvalidAmountException.class);
    }
}
