package com.zippay.wallet.repository;

import com.zippay.common.money.Currency;
import com.zippay.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    List<Wallet> findAllByUserIdOrderByIdAsc(Long userId);

    // 소유자 조건을 쿼리에 같이 건다 → 남의 지갑은 "없음"과 구분되지 않는다 (404)
    Optional<Wallet> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndCurrency(Long userId, Currency currency);

    /**
     * SELECT ... FOR UPDATE — 커밋할 때까지 다른 트랜잭션의 락·수정을 막는다 (Lost Update 방지).
     * 데드락 방지 규칙(지갑 id 오름차순, 필요한 락을 처음에 한꺼번에)을 한 곳에서 지키기 위해
     * LedgerService만 호출한다. 다른 곳에서 직접 부르지 말 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
