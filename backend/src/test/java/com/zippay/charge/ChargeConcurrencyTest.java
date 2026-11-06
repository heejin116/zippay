package com.zippay.charge;

import com.zippay.TestcontainersConfiguration;
import com.zippay.auth.domain.Email;
import com.zippay.auth.domain.User;
import com.zippay.auth.repository.UserRepository;
import com.zippay.charge.service.ChargeResult;
import com.zippay.charge.service.ChargeService;
import com.zippay.common.idempotency.IdempotencyKeyReusedException;
import com.zippay.common.money.Currency;
import com.zippay.ledger.repository.LedgerReconciliationRepository;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.zippay.common.money.Currency.KRW;
import static com.zippay.common.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등성의 동시성 경로 검증. 순차 재요청은 선조회에서 끝나지만, 동시에 들어온 같은 키 요청은
 * 전부 선조회를 통과한 뒤 (같은 지갑이면) 지갑 락에서, (다른 지갑이면) UNIQUE 인덱스에서 줄을 선다.
 * 패자는 UNIQUE 위반 → 롤백 → 트랜잭션 밖 재조회 경로를 탄다. 테스트 트랜잭션 없이 실제 커밋으로 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChargeConcurrencyTest {

    // Hikari 기본 풀(10)보다 작게: 스레드마다 커넥션을 하나씩 잡고 락을 기다린다
    static final int THREADS = 8;

    @Autowired ChargeService chargeService;
    @Autowired UserRepository userRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerReconciliationRepository reconciliation;

    @Test
    void concurrent_requests_with_same_key_on_same_wallet_charge_exactly_once() throws Exception {
        Long userId = newUser();
        Long walletId = newWallet(userId, KRW);
        String key = UUID.randomUUID().toString();

        List<Object> outcomes = runConcurrently(Collections.<Callable<ChargeResult>>nCopies(THREADS,
                () -> chargeService.charge(userId, walletId, key, new BigDecimal("10000"))));

        List<ChargeResult> results = successes(outcomes);
        assertThat(results).hasSize(THREADS);                                    // 전원 성공 응답 (실패 없음)
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);          // 실제 실행은 1번
        assertThat(results).extracting(r -> r.response().transactionId()).containsOnly(results.get(0).response().transactionId());
        assertThat(results).extracting(r -> r.response().balanceAfter()).containsOnly("10000");
        assertThat(balanceOf(walletId)).isEqualByComparingTo("10000");
        assertReconciled();
    }

    @Test
    void concurrent_requests_with_same_key_on_different_wallets_one_wins_and_other_gets_422() throws Exception {
        // 지갑이 달라 지갑 락으로는 직렬화되지 않는다 → uk_ledger_tx_user_idem_key가 최종 방어
        Long userId = newUser();
        Long krw = newWallet(userId, KRW);
        Long usd = newWallet(userId, USD);
        String key = UUID.randomUUID().toString();

        List<Object> outcomes = runConcurrently(List.<Callable<ChargeResult>>of(
                () -> chargeService.charge(userId, krw, key, new BigDecimal("1000")),
                () -> chargeService.charge(userId, usd, key, new BigDecimal("10.00"))));

        assertThat(successes(outcomes)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof IdempotencyKeyReusedException).hasSize(1);
        boolean krwWon = balanceOf(krw).signum() > 0;
        boolean usdWon = balanceOf(usd).signum() > 0;
        assertThat(krwWon ^ usdWon).as("정확히 한 지갑만 충전").isTrue();
        assertReconciled();
    }

    @Test
    void concurrent_requests_with_different_keys_are_all_applied() throws Exception {
        Long userId = newUser();
        Long walletId = newWallet(userId, KRW);

        List<Callable<ChargeResult>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = UUID.randomUUID().toString();
            tasks.add(() -> chargeService.charge(userId, walletId, key, new BigDecimal("1000")));
        }
        List<ChargeResult> results = successes(runConcurrently(tasks));

        assertThat(results).hasSize(THREADS).noneMatch(ChargeResult::replayed);
        // 지갑 락으로 직렬화되므로 balance_after는 1000, 2000, ..., 8000이 하나씩 나와야 한다 (Lost Update 없음)
        assertThat(results).extracting(r -> new BigDecimal(r.response().balanceAfter()).intValue())
                .containsExactlyInAnyOrder(1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000);
        assertThat(balanceOf(walletId)).isEqualByComparingTo(String.valueOf(THREADS * 1000));
        assertReconciled();
    }

    // ---------- 헬퍼 ----------

    /** 모든 작업을 동시에 출발시키고, 각 결과(성공 값 또는 던진 예외)를 순서대로 돌려준다. */
    private static <T> List<Object> runConcurrently(List<? extends Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("동시 요청이 30초 안에 끝나지 않음 (데드락 의심)", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<ChargeResult> successes(List<Object> outcomes) {
        List<ChargeResult> results = new ArrayList<>();
        for (Object o : outcomes) {
            if (o instanceof ChargeResult r) {
                results.add(r);
            } else if (!(o instanceof IdempotencyKeyReusedException)) {
                throw new AssertionError("예상하지 못한 실패", (Throwable) o);
            }
        }
        return results;
    }

    private void assertReconciled() {
        assertThat(reconciliation.findUnbalancedTransactionIds()).isEmpty();
        assertThat(reconciliation.findWalletIdsWithBalanceMismatch()).isEmpty();
        assertThat(reconciliation.findCurrenciesWithTotalMismatch()).isEmpty();
    }

    private Long newUser() {
        return userRepository.save(User.create(new Email("concurrency-" + UUID.randomUUID() + "@zippay.com"), "hash")).getId();
    }

    private Long newWallet(Long userId, Currency currency) {
        return walletRepository.save(Wallet.create(userId, currency)).getId();
    }

    private BigDecimal balanceOf(Long walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }
}
