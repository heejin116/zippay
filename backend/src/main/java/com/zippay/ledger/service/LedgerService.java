package com.zippay.ledger.service;

import com.zippay.common.money.Currency;
import com.zippay.ledger.domain.LedgerEntry;
import com.zippay.ledger.domain.LedgerTransaction;
import com.zippay.ledger.domain.LedgerTransactionType;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import com.zippay.ledger.repository.LedgerTransactionRepository;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.exception.WalletNotFoundException;
import com.zippay.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원장 기록의 단일 진입점 (내부용, 외부 API 없음). 충전(STEP 5)·송금(STEP 6) 서비스가 호출한다.
 * 잔액을 움직이는 모든 경로가 여기를 거치므로 락 순서·잔액 캐시 갱신·불변식 검증이 한 곳에 모인다.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final WalletRepository walletRepository;

    public LedgerService(LedgerTransactionRepository ledgerTransactionRepository,
                         WalletRepository walletRepository) {
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.walletRepository = walletRepository;
    }

    /**
     * 처리 순서 (전부 한 트랜잭션 — 중간에 실패하면 잔액 변경까지 전부 롤백):
     *   1. 지갑 락: id 오름차순으로, 필요한 지갑을 처음에 한꺼번에 (데드락 방지)
     *   2. 지갑 통화 확인 → 잔액 변경 (Wallet.increase/decrease가 음수 잔액 거부)
     *   3. 변경 직후 잔액으로 balance_after를 채워 분개 생성
     *   4. LedgerTransaction.create()가 통화 일치·합 0·유형별 구성 검증 → 헤더+분개 한 번에 저장
     * 같은 (user, idempotency_key)가 이미 커밋돼 있으면 4의 INSERT에서 UNIQUE 위반
     * → DataIntegrityViolationException → 전체 롤백. 재조회·응답 재생은 호출자(STEP 5)가 트랜잭션 밖에서 처리.
     */
    @Transactional
    public LedgerTransaction post(Long userId,
                                  String idempotencyKey,
                                  String requestHash,
                                  LedgerTransactionType type,
                                  Currency currency,
                                  List<Posting> postings) {
        Map<Long, Wallet> wallets = lockWalletsInIdOrder(postings, currency);

        List<LedgerEntry> entries = new ArrayList<>();
        for (Posting posting : postings) {
            if (posting.isWallet()) {
                Wallet wallet = wallets.get(posting.walletId());
                apply(wallet, posting.amount());
                // 락을 잡은 상태의 "변경 직후 잔액" — 다른 트랜잭션이 끼어들 수 없으므로 정확하다
                entries.add(LedgerEntry.wallet(wallet.getId(), currency, posting.amount(), wallet.getBalance()));
            } else {
                entries.add(LedgerEntry.external(currency, posting.amount()));
            }
        }

        LedgerTransaction transaction =
                LedgerTransaction.create(userId, idempotencyKey, requestHash, type, currency, entries);
        // IDENTITY라 save() 시점에 헤더 INSERT → cascade로 분개 INSERT. wallets UPDATE는 커밋 직전 flush 때 나간다.
        return ledgerTransactionRepository.save(transaction);
    }

    /**
     * 송금 방향(10→20이든 20→10이든)과 무관하게 항상 작은 id부터 잠근다.
     * 모든 트랜잭션이 같은 방향으로만 줄을 서므로 순환 대기(데드락)가 생기지 않는다.
     */
    private Map<Long, Wallet> lockWalletsInIdOrder(List<Posting> postings, Currency currency) {
        List<Long> walletIds = postings.stream()
                .filter(Posting::isWallet)
                .map(Posting::walletId)
                .distinct()
                .sorted()
                .toList();

        Map<Long, Wallet> locked = new HashMap<>();
        for (Long walletId : walletIds) {
            Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                    .orElseThrow(() -> new WalletNotFoundException("지갑을 찾을 수 없습니다."));
            // fk_ledger_entries_wallet_currency와 이중 방어. 잔액을 건드리기 전에 확인한다.
            if (wallet.getCurrency() != currency) {
                throw new InvalidLedgerTransactionException(
                        "지갑 통화(" + wallet.getCurrency() + ")가 거래 통화(" + currency + ")와 다릅니다.");
            }
            locked.put(walletId, wallet);
        }
        return locked;
    }

    private static void apply(Wallet wallet, BigDecimal signedAmount) {
        if (signedAmount.signum() > 0) {
            wallet.increase(signedAmount);
        } else {
            wallet.decrease(signedAmount.negate());   // 0이면 Wallet이 InvalidAmountException
        }
    }
}
