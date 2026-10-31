package com.zippay.wallet.repository;

import com.zippay.TestcontainersConfiguration;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.common.money.Currency;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class WalletRepositoryTest {

    @Autowired WalletRepository walletRepository;
    @Autowired TestEntityManager em;

    Long userId;
    Long otherUserId;

    @BeforeEach
    void setUp() {
        // wallets.user_id에 FK가 걸려 있으므로 실제 users 행이 필요하다
        userId = em.persistAndGetId(User.create(new Email("wallet@zippay.com"), "hash"), Long.class);
        otherUserId = em.persistAndGetId(User.create(new Email("other@zippay.com"), "hash"), Long.class);
    }

    @Test
    void find_all_by_user_returns_own_wallets_in_id_order() {
        walletRepository.save(Wallet.create(userId, Currency.KRW));
        walletRepository.save(Wallet.create(userId, Currency.USD));
        walletRepository.save(Wallet.create(otherUserId, Currency.KRW));
        em.flush();
        em.clear();

        List<Wallet> found = walletRepository.findAllByUserIdOrderByIdAsc(userId);

        assertThat(found).extracting(Wallet::getCurrency).containsExactly(Currency.KRW, Currency.USD);
        assertThat(found.get(0).getBalance()).isEqualByComparingTo("0");
        assertThat(found.get(0).getCreatedAt()).isNotNull();   // DB DEFAULT now()가 채움
    }

    @Test
    void find_by_id_and_user_hides_other_users_wallet() {
        Long walletId = walletRepository.save(Wallet.create(userId, Currency.KRW)).getId();
        em.flush();
        em.clear();

        assertThat(walletRepository.findByIdAndUserId(walletId, userId)).isPresent();
        assertThat(walletRepository.findByIdAndUserId(walletId, otherUserId)).isEmpty();
    }

    @Test
    void same_user_and_currency_violates_unique_constraint() {
        walletRepository.saveAndFlush(Wallet.create(userId, Currency.KRW));

        assertThatThrownBy(() -> walletRepository.saveAndFlush(Wallet.create(userId, Currency.KRW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_wallets_user_currency");
    }

    @Test
    void db_rejects_negative_balance() {
        // 도메인을 우회해도(네이티브 SQL) DB CHECK가 막는지 확인 — 이중 방어의 DB 쪽
        Long walletId = walletRepository.saveAndFlush(Wallet.create(userId, Currency.KRW)).getId();

        assertThatThrownBy(() -> updateBalance(walletId, new BigDecimal("-1")))
                .rootCause().hasMessageContaining("ck_wallets_balance_nonneg");
    }

    @Test
    void db_rejects_balance_exceeding_currency_scale() {
        Long krwId = walletRepository.saveAndFlush(Wallet.create(userId, Currency.KRW)).getId();

        assertThatThrownBy(() -> updateBalance(krwId, new BigDecimal("0.5")))
                .rootCause().hasMessageContaining("ck_wallets_balance_scale");
    }

    @Test
    void db_allows_two_decimal_places_for_usd() {
        Long usdId = walletRepository.saveAndFlush(Wallet.create(userId, Currency.USD)).getId();

        updateBalance(usdId, new BigDecimal("12.34"));
        em.clear();

        assertThat(walletRepository.findById(usdId).orElseThrow().getBalance()).isEqualByComparingTo("12.34");
    }

    private void updateBalance(Long walletId, BigDecimal balance) {
        em.getEntityManager()
                .createNativeQuery("UPDATE wallets SET balance = :balance WHERE id = :id")
                .setParameter("balance", balance)
                .setParameter("id", walletId)
                .executeUpdate();
    }
}
