package com.zippay.ledger.repository;

import com.zippay.ledger.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    // 멱등 재요청 시 기존 거래 재조회용 (STEP 5). uk_ledger_tx_user_idem_key 인덱스를 탄다.
    Optional<LedgerTransaction> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
