package com.zippay.ledger.service;

import com.zippay.TestcontainersConfiguration;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.auth.repository.UserRepository;
import com.zippay.ledger.repository.LedgerReconciliationRepository;
import com.zippay.ledger.repository.LedgerTransactionRepository;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.exception.InsufficientBalanceException;
import com.zippay.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.zippay.common.money.Currency.KRW;
import static com.zippay.ledger.domain.LedgerTransactionType.CHARGE;
import static com.zippay.ledger.domain.LedgerTransactionType.TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 커밋·롤백 경계에서 post()의 원자성을 확인한다.
 * 테스트 트랜잭션을 끄고(NOT_SUPPORTED) 매 호출을 TransactionTemplate으로 감싸, 각 post()가 진짜로 커밋/롤백되게 한다.
 * 커밋된 데이터는 남는다(원장은 append-only라 지울 수도 없다) → 사용자·지갑·키를 매번 새로 만든다.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, LedgerService.class, LedgerReconciliationRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerServiceRollbackTest {

    static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired LedgerService ledgerService;
    @Autowired LedgerReconciliationRepository reconciliation;
    @Autowired LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void duplicate_idempotency_key_rolls_back_whole_transaction_including_balance_change() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long userId = newUser(tx);
        Long walletId = newWallet(tx, userId);
        String sameKey = UUID.randomUUID().toString();

        tx.executeWithoutResult(s -> charge(userId, walletId, sameKey, "10000"));

        // 같은 키로 재요청: 지갑 락 → 잔액 +5000(메모리) → 헤더 INSERT에서 UNIQUE 위반 → 트랜잭션 전체 롤백
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> charge(userId, walletId, sameKey, "5000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("uk_ledger_tx_user_idem_key");

        assertThat(balance(tx, walletId)).isEqualByComparingTo("10000");   // 두 번째 +5000은 반영되지 않음
        Integer entryCount = tx.execute(s -> ledgerTransactionRepository.findByUserIdAndIdempotencyKey(userId, sameKey)
                .orElseThrow().getEntries().size());
        assertThat(entryCount).isEqualTo(2);                               // 첫 거래만 그대로

        List<Long> mismatched = tx.execute(s -> reconciliation.findWalletIdsWithBalanceMismatch());
        assertThat(mismatched).doesNotContain(walletId);
    }

    @Test
    void insufficient_balance_rolls_back_and_leaves_both_wallets_unchanged() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long aliceId = newUser(tx);
        Long bobId = newUser(tx);
        Long from = newWallet(tx, aliceId);
        Long to = newWallet(tx, bobId);
        tx.executeWithoutResult(s -> charge(aliceId, from, UUID.randomUUID().toString(), "1000"));

        // 입금 줄을 먼저 둔다: bob 지갑이 메모리에서 +1001 된 "뒤에" alice 출금이 실패 → 롤백이 bob 쪽 변경까지 되돌리는지 확인
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerService.post(
                aliceId, UUID.randomUUID().toString(), HASH, TRANSFER, KRW, List.of(
                        Posting.wallet(to, new BigDecimal("1001")),
                        Posting.wallet(from, new BigDecimal("-1001"))))))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balance(tx, from)).isEqualByComparingTo("1000");
        assertThat(balance(tx, to)).isEqualByComparingTo("0");
    }

    private void charge(Long userId, Long walletId, String key, String amount) {
        BigDecimal a = new BigDecimal(amount);
        ledgerService.post(userId, key, HASH, CHARGE, KRW, List.of(
                Posting.wallet(walletId, a), Posting.external(a.negate())));
    }

    private Long newUser(TransactionTemplate tx) {
        return tx.execute(s -> userRepository.save(
                User.create(new Email("rollback-" + UUID.randomUUID() + "@zippay.com"), "hash")).getId());
    }

    private Long newWallet(TransactionTemplate tx, Long userId) {
        return tx.execute(s -> walletRepository.save(Wallet.create(userId, KRW)).getId());
    }

    private BigDecimal balance(TransactionTemplate tx, Long walletId) {
        return tx.execute(s -> walletRepository.findById(walletId).orElseThrow().getBalance());
    }
}
