package com.zippay.ledger.repository;

import com.zippay.TestcontainersConfiguration;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.common.money.Currency;
import com.zippay.ledger.domain.AccountType;
import com.zippay.ledger.domain.LedgerEntry;
import com.zippay.ledger.domain.LedgerTransaction;
import com.zippay.wallet.domain.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static com.zippay.ledger.domain.LedgerTransactionType.CHARGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class LedgerTransactionRepositoryTest {

    static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired TestEntityManager em;

    Long userId;
    Long walletId;

    @BeforeEach
    void setUp() {
        // ledger_transactions.user_id, ledger_entries.wallet_id에 FK가 걸려 있으므로 실제 행이 필요하다
        userId = em.persistAndGetId(User.create(new Email("ledger@zippay.com"), "hash"), Long.class);
        walletId = em.persistAndGetId(Wallet.create(userId, Currency.KRW), Long.class);
    }

    LedgerTransaction charge(String key, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return LedgerTransaction.create(userId, key, HASH, CHARGE, Currency.KRW, List.of(
                LedgerEntry.wallet(walletId, Currency.KRW, a, a),
                LedgerEntry.external(Currency.KRW, a.negate())));
    }

    @Test
    void saving_transaction_cascades_entries_and_round_trips() {
        Long txId = ledgerTransactionRepository.save(charge("key-1", "10000")).getId();
        em.flush();
        em.clear();   // 1차 캐시를 비워 실제 DB 왕복 확인

        LedgerTransaction found = ledgerTransactionRepository.findById(txId).orElseThrow();

        assertThat(found.getCreatedAt()).isNotNull();                // DB DEFAULT now()
        assertThat(found.getRequestHash()).isEqualTo(HASH);          // char(64) 매핑 (공백 패딩 없음)
        assertThat(found.getEntries()).hasSize(2);
        LedgerEntry wallet = found.getEntries().get(0);
        LedgerEntry external = found.getEntries().get(1);
        assertThat(wallet.getAccountType()).isEqualTo(AccountType.WALLET);
        assertThat(wallet.getCurrency()).isEqualTo(Currency.KRW);
        assertThat(wallet.getWalletId()).isEqualTo(walletId);
        assertThat(wallet.getAmount()).isEqualByComparingTo("10000");
        assertThat(wallet.getBalanceAfter()).isEqualByComparingTo("10000");
        assertThat(external.getAccountType()).isEqualTo(AccountType.EXTERNAL);
        assertThat(external.getWalletId()).isNull();
        assertThat(external.getAmount()).isEqualByComparingTo("-10000");
    }

    @Test
    void find_by_user_and_idempotency_key() {
        ledgerTransactionRepository.save(charge("key-1", "10000"));
        em.flush();
        em.clear();

        assertThat(ledgerTransactionRepository.findByUserIdAndIdempotencyKey(userId, "key-1")).isPresent();
        assertThat(ledgerTransactionRepository.findByUserIdAndIdempotencyKey(userId, "key-2")).isEmpty();
    }

    @Test
    void same_user_and_idempotency_key_violates_unique_constraint() {
        ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000"));

        assertThatThrownBy(() -> ledgerTransactionRepository.saveAndFlush(charge("key-1", "5000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_ledger_tx_user_idem_key");
    }

    // ---------- append-only: 도메인을 우회한 네이티브 SQL도 DB 트리거가 막는지 ----------

    @Test
    void db_rejects_update_of_entry() {
        ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000"));

        assertThatThrownBy(() -> native_("UPDATE ledger_entries SET amount = 1"))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void db_rejects_delete_of_transaction() {
        ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000"));

        assertThatThrownBy(() -> native_("DELETE FROM ledger_transactions"))
                .rootCause().hasMessageContaining("append-only");
    }

    // ---------- 계정 유형별 컬럼 조합: 팩터리를 우회해도 CHECK가 막는지 ----------

    @Test
    void db_rejects_wallet_entry_without_wallet_id() {
        Long txId = ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000")).getId();

        assertThatThrownBy(() -> native_(
                "INSERT INTO ledger_entries (transaction_id, account_type, wallet_id, currency, amount, balance_after) "
                        + "VALUES (" + txId + ", 'WALLET', NULL, 'KRW', 1, 1)"))
                .rootCause().hasMessageContaining("ck_ledger_entries_account_shape");
    }

    // ---------- 통화: 도메인을 우회해도 복합 FK·CHECK가 막는지 ----------

    @Test
    void db_rejects_entry_currency_different_from_transaction() {
        Long txId = ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000")).getId();   // KRW 거래

        assertThatThrownBy(() -> native_(
                "INSERT INTO ledger_entries (transaction_id, account_type, wallet_id, currency, amount, balance_after) "
                        + "VALUES (" + txId + ", 'EXTERNAL', NULL, 'USD', -1, NULL)"))
                .rootCause().hasMessageContaining("fk_ledger_entries_tx_currency");
    }

    @Test
    void db_rejects_entry_currency_different_from_wallet() {
        // USD 거래 헤더에 KRW 지갑을 USD 분개로 연결 → 지갑 통화 불일치
        native_("INSERT INTO ledger_transactions (user_id, idempotency_key, request_hash, type, currency) "
                + "VALUES (" + userId + ", 'usd-key', '" + HASH + "', 'CHARGE', 'USD')");
        Long usdTxId = ledgerTransactionRepository.findByUserIdAndIdempotencyKey(userId, "usd-key").orElseThrow().getId();

        assertThatThrownBy(() -> native_(
                "INSERT INTO ledger_entries (transaction_id, account_type, wallet_id, currency, amount, balance_after) "
                        + "VALUES (" + usdTxId + ", 'WALLET', " + walletId + ", 'USD', 1, 1)"))
                .rootCause().hasMessageContaining("fk_ledger_entries_wallet_currency");
    }

    @Test
    void db_rejects_amount_exceeding_currency_scale() {
        Long txId = ledgerTransactionRepository.saveAndFlush(charge("key-1", "10000")).getId();

        assertThatThrownBy(() -> native_(
                "INSERT INTO ledger_entries (transaction_id, account_type, wallet_id, currency, amount, balance_after) "
                        + "VALUES (" + txId + ", 'EXTERNAL', NULL, 'KRW', -0.5, NULL)"))
                .rootCause().hasMessageContaining("ck_ledger_entries_amount_scale");
    }

    private void native_(String sql) {
        em.getEntityManager().createNativeQuery(sql).executeUpdate();
    }
}
