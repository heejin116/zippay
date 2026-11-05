package com.zippay.ledger.service;

import com.zippay.TestcontainersConfiguration;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.auth.repository.UserRepository;
import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.ledger.domain.AccountType;
import com.zippay.ledger.domain.LedgerEntry;
import com.zippay.ledger.domain.LedgerTransaction;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import com.zippay.ledger.repository.LedgerReconciliationRepository;
import com.zippay.ledger.repository.LedgerTransactionRepository;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.exception.InsufficientBalanceException;
import com.zippay.wallet.exception.WalletNotFoundException;
import com.zippay.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.zippay.common.money.Currency.KRW;
import static com.zippay.common.money.Currency.USD;
import static com.zippay.ledger.domain.LedgerTransactionType.CHARGE;
import static com.zippay.ledger.domain.LedgerTransactionType.TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TestcontainersConfiguration.class, LedgerService.class, LedgerReconciliationRepository.class})
class LedgerServiceTest {

    static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired LedgerService ledgerService;
    @Autowired LedgerReconciliationRepository reconciliation;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    Long aliceId;
    Long bobId;

    @BeforeEach
    void setUp() {
        aliceId = newUser();
        bobId = newUser();
    }

    // ---------- 충전 ----------

    @Test
    void charge_increases_wallet_and_records_wallet_and_external_entries() {
        Long krw = newWallet(aliceId, KRW);

        LedgerTransaction tx = charge(aliceId, krw, KRW, "10000");
        em.flush();
        em.clear();

        assertThat(balanceOf(krw)).isEqualByComparingTo("10000");
        LedgerTransaction found = ledgerTransactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(found.getEntries()).extracting(LedgerEntry::getAccountType)
                .containsExactly(AccountType.WALLET, AccountType.EXTERNAL);
        assertThat(found.getEntries().get(0).getBalanceAfter()).isEqualByComparingTo("10000");
        assertThat(found.getEntries().get(1).getAmount()).isEqualByComparingTo("-10000");
    }

    // ---------- 송금 ----------

    @Test
    void transfer_moves_balance_and_records_balance_after_of_each_wallet() {
        Long from = newWallet(aliceId, KRW);
        Long to = newWallet(bobId, KRW);
        charge(aliceId, from, KRW, "10000");

        LedgerTransaction tx = transfer(aliceId, from, to, KRW, "3000");
        em.flush();
        em.clear();

        assertThat(balanceOf(from)).isEqualByComparingTo("7000");
        assertThat(balanceOf(to)).isEqualByComparingTo("3000");
        List<LedgerEntry> entries = ledgerTransactionRepository.findById(tx.getId()).orElseThrow().getEntries();
        assertThat(entries).extracting(LedgerEntry::getWalletId).containsExactly(from, to);
        assertThat(entries).extracting(LedgerEntry::getBalanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("7000"), new BigDecimal("3000"));
    }

    @Test
    void transfer_from_higher_id_to_lower_id_works() {
        // 락은 항상 작은 id부터 잡지만, 출금·입금 방향은 그와 무관해야 한다
        Long lower = newWallet(bobId, KRW);
        Long higher = newWallet(aliceId, KRW);
        charge(aliceId, higher, KRW, "5000");

        transfer(aliceId, higher, lower, KRW, "5000");
        em.flush();
        em.clear();

        assertThat(balanceOf(higher)).isEqualByComparingTo("0");
        assertThat(balanceOf(lower)).isEqualByComparingTo("5000");
    }

    // ---------- 거부 ----------

    @Test
    void insufficient_balance_is_rejected_and_nothing_is_recorded() {
        Long from = newWallet(aliceId, KRW);
        Long to = newWallet(bobId, KRW);
        charge(aliceId, from, KRW, "1000");

        assertThatThrownBy(() -> transfer(aliceId, from, to, KRW, "1001"))
                .isInstanceOf(InsufficientBalanceException.class);
        em.flush();
        em.clear();

        assertThat(balanceOf(from)).isEqualByComparingTo("1000");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
        assertThat(transactionCountOf(aliceId)).isEqualTo(1);   // 충전 1건만, 송금 헤더는 남지 않음
    }

    @Test
    void wallet_currency_different_from_transaction_is_rejected() {
        Long usd = newWallet(aliceId, USD);

        assertThatThrownBy(() -> charge(aliceId, usd, KRW, "10000"))
                .isInstanceOf(InvalidLedgerTransactionException.class)
                .hasMessageContaining("통화");
    }

    @Test
    void unknown_wallet_is_rejected() {
        assertThatThrownBy(() -> charge(aliceId, Long.MAX_VALUE, KRW, "10000"))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void zero_amount_posting_is_rejected() {
        Long krw = newWallet(aliceId, KRW);

        assertThatThrownBy(() -> ledgerService.post(aliceId, key(), HASH, CHARGE, KRW, List.of(
                Posting.wallet(krw, BigDecimal.ZERO), Posting.external(BigDecimal.ZERO))))
                .isInstanceOf(InvalidAmountException.class);   // Wallet.decrease(0)에서 거부
    }

    // ---------- 대사 쿼리 ----------
    // 대사 쿼리는 DB 전체를 본다. 다른 테스트가 커밋한 데이터도 전부 원장을 거친 정상 데이터여야 통과한다.

    @Test
    void reconciliation_finds_nothing_after_mixed_charges_and_transfers() {
        Long aliceKrw = newWallet(aliceId, KRW);
        Long bobKrw = newWallet(bobId, KRW);
        Long aliceUsd = newWallet(aliceId, USD);
        Long bobUsd = newWallet(bobId, USD);

        charge(aliceId, aliceKrw, KRW, "50000");
        charge(bobId, bobKrw, KRW, "20000");
        transfer(aliceId, aliceKrw, bobKrw, KRW, "15000");
        transfer(bobId, bobKrw, aliceKrw, KRW, "5000");    // 역방향 송금
        charge(aliceId, aliceUsd, USD, "100.25");
        transfer(aliceId, aliceUsd, bobUsd, USD, "0.75");
        em.flush();

        assertThat(reconciliation.findUnbalancedTransactionIds()).isEmpty();
        assertThat(reconciliation.findWalletIdsWithBalanceMismatch()).isEmpty();
        assertThat(reconciliation.findCurrenciesWithTotalMismatch()).isEmpty();
    }

    @Test
    void reconciliation_detects_balance_cache_drift() {
        // 쿼리가 "항상 빈 결과"가 아니라 실제로 위반을 잡는지 확인 — 원장을 거치지 않고 잔액만 바꾼다
        Long krw = newWallet(aliceId, KRW);
        charge(aliceId, krw, KRW, "10000");
        em.flush();

        em.getEntityManager().createNativeQuery("UPDATE wallets SET balance = balance + 1 WHERE id = " + krw)
                .executeUpdate();

        assertThat(reconciliation.findWalletIdsWithBalanceMismatch()).contains(krw);
        assertThat(reconciliation.findCurrenciesWithTotalMismatch()).contains("KRW");
    }

    @Test
    void reconciliation_detects_transaction_without_entries() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO ledger_transactions (user_id, idempotency_key, request_hash, type, currency) "
                        + "VALUES (" + aliceId + ", 'orphan', '" + HASH + "', 'CHARGE', 'KRW')").executeUpdate();
        Long orphanId = ledgerTransactionRepository.findByUserIdAndIdempotencyKey(aliceId, "orphan").orElseThrow().getId();

        assertThat(reconciliation.findUnbalancedTransactionIds()).contains(orphanId);
    }

    // ---------- 헬퍼 ----------

    LedgerTransaction charge(Long userId, Long walletId, Currency currency, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return ledgerService.post(userId, key(), HASH, CHARGE, currency, List.of(
                Posting.wallet(walletId, a), Posting.external(a.negate())));
    }

    LedgerTransaction transfer(Long userId, Long fromWalletId, Long toWalletId, Currency currency, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return ledgerService.post(userId, key(), HASH, TRANSFER, currency, List.of(
                Posting.wallet(fromWalletId, a.negate()), Posting.wallet(toWalletId, a)));
    }

    Long newUser() {
        return em.persistAndGetId(User.create(new Email(uniqueEmail()), "hash"), Long.class);
    }

    Long newWallet(Long userId, Currency currency) {
        return em.persistAndGetId(Wallet.create(userId, currency), Long.class);
    }

    long transactionCountOf(Long userId) {
        return em.getEntityManager()
                .createQuery("select count(t) from LedgerTransaction t where t.userId = :userId", Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    BigDecimal balanceOf(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }

    static String key() { return UUID.randomUUID().toString(); }

    static String uniqueEmail() { return "ledger-" + UUID.randomUUID() + "@zippay.com"; }
}
