package com.zippay.wallet.repository;

import com.zippay.common.money.Currency;
import com.zippay.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    List<Wallet> findAllByUserIdOrderByIdAsc(Long userId);

    // 소유자 조건을 쿼리에 같이 건다 → 남의 지갑은 "없음"과 구분되지 않는다 (404)
    Optional<Wallet> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndCurrency(Long userId, Currency currency);
}
