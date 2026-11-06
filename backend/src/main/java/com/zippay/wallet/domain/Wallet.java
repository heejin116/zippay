package com.zippay.wallet.domain;

import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.wallet.exception.BalanceLimitExceededException;
import com.zippay.wallet.exception.InsufficientBalanceException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "wallets")
public class Wallet {

    /**
     * 지갑 보유 한도 (정책 가정값). 충전이든 송금 입금이든 잔액이 늘어나는 모든 경로에 적용되는 지갑 불변식이라
     * 충전 도메인이 아니라 여기(increase)에 둔다. 잔액 음수 금지(decrease)와 짝.
     * DB CHECK는 걸지 않는다 — 무결성 규칙이 아니라 바뀔 수 있는 정책 값이라, CHECK로 박으면 한도 조정마다 마이그레이션이 필요하다.
     */
    private static final Map<Currency, BigDecimal> MAX_BALANCE = new EnumMap<>(Map.of(
            Currency.KRW, new BigDecimal("2000000"),
            Currency.USD, new BigDecimal("2000.00"),
            Currency.JPY, new BigDecimal("200000")));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 도메인 엔티티는 @ManyToOne 대신 id로 참조 (무결성은 FK가 보장)
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    // 원장의 캐시. increase/decrease는 원장 기록과 같은 트랜잭션, 지갑 락을 잡은 상태에서만 호출한다 (STEP 5·6)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Wallet() {}

    public static Wallet create(Long userId, Currency currency) {
        Wallet wallet = new Wallet();
        wallet.userId = Objects.requireNonNull(userId, "userId");
        wallet.currency = Objects.requireNonNull(currency, "currency");
        wallet.balance = BigDecimal.ZERO;               // 개설 시 잔액 0
        return wallet;
    }

    public void increase(BigDecimal amount) {
        validateAmount(amount);
        BigDecimal limit = maxBalance(currency);
        if (balance.add(amount).compareTo(limit) > 0) {
            throw new BalanceLimitExceededException(
                    currency.name() + " 지갑 보유 한도(" + limit.toPlainString() + ")를 초과합니다.");
        }
        balance = balance.add(amount);
    }

    public static BigDecimal maxBalance(Currency currency) {
        return Objects.requireNonNull(MAX_BALANCE.get(currency), "보유 한도 미정의 통화: " + currency);
    }

    public void decrease(BigDecimal amount) {
        validateAmount(amount);
        // 잔액 음수 금지 — DB의 ck_wallets_balance_nonneg와 이중 방어
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException("잔액이 부족합니다.");
        }
        balance = balance.subtract(amount);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("금액은 0보다 커야 합니다.");
        }
        currency.requireValidScale(amount);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Currency getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
