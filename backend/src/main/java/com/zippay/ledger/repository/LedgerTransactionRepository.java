package com.zippay.ledger.repository;

import com.zippay.ledger.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    // 키로 헤더만 조회 (테스트·점검용). uk_ledger_tx_user_idem_key 인덱스를 탄다.
    Optional<LedgerTransaction> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * 분개까지 한 번에 읽는다. 충전·송금 서비스는 트랜잭션 밖에서 응답을 만들므로(open-in-view: false)
     * 지연 로딩이면 getEntries()에서 LazyInitializationException이 난다.
     */
    @Query("""
            select distinct t from LedgerTransaction t
            join fetch t.entries
            where t.userId = :userId and t.idempotencyKey = :idempotencyKey
            """)
    Optional<LedgerTransaction> findWithEntriesByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                                                         @Param("idempotencyKey") String idempotencyKey);
}
