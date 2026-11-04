package com.zippay.ledger.domain;

import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 분개 1줄. 부호 있는 단일 금액(+증가 / −감소).
 * append-only: @Immutable(Hibernate가 UPDATE를 만들지 않음) + V3 트리거(DB가 UPDATE/DELETE 거부) 이중 방어.
 * 단독으로 저장하지 않고 LedgerTransaction.create()를 통해서만 거래에 묶인다.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 같은 집합(거래) 안의 부모 참조라 "다른 도메인은 Long id" 규칙의 예외.
    // 양방향(mappedBy)이라 INSERT 시점에 FK가 함께 들어가고, FK를 채우는 UPDATE가 생기지 않는다 (트리거와 충돌 방지).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 10, updatable = false)
    private AccountType accountType;

    @Column(name = "wallet_id", updatable = false)
    private Long walletId;

    // 분개 단위 통화. DB가 (transaction_id, currency)·(wallet_id, currency) 복합 FK로 헤더·지갑 통화와 일치를 강제한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    // 이 분개 반영 직후의 지갑 잔액. EXTERNAL은 NULL
    @Column(name = "balance_after", precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    protected LedgerEntry() {}

    /**
     * 지갑 분개. currency는 지갑의 통화, balanceAfter는 Wallet.increase/decrease 직후의 잔액을 넣는다.
     * (지갑 통화와의 일치는 서비스에서 지갑을 락 잡은 뒤 확인하고, DB 복합 FK가 한 번 더 막는다)
     */
    public static LedgerEntry wallet(Long walletId, Currency currency, BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.accountType = AccountType.WALLET;
        entry.walletId = Objects.requireNonNull(walletId, "walletId");
        entry.currency = Objects.requireNonNull(currency, "currency");
        entry.amount = requireValidAmount(currency, amount);
        entry.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
        // ck_ledger_entries_balance_after_nonneg와 이중 방어
        if (balanceAfter.signum() < 0) {
            throw new InvalidAmountException("거래 후 잔액은 음수일 수 없습니다: " + balanceAfter.toPlainString());
        }
        // ck_ledger_entries_balance_after_scale과 이중 방어
        currency.requireValidScale(balanceAfter);
        return entry;
    }

    /** 외부(은행) 상대계정 분개. 잔액 개념이 없으므로 walletId·balanceAfter 없음 (ck_ledger_entries_account_shape) */
    public static LedgerEntry external(Currency currency, BigDecimal amount) {
        LedgerEntry entry = new LedgerEntry();
        entry.accountType = AccountType.EXTERNAL;
        entry.currency = Objects.requireNonNull(currency, "currency");
        entry.amount = requireValidAmount(currency, amount);
        return entry;
    }

    /** LedgerTransaction.create()에서만 호출. 한 분개가 두 거래에 묶이는 것을 막는다. */
    void attachTo(LedgerTransaction transaction) {
        if (this.transaction != null) {
            throw new InvalidLedgerTransactionException("이미 다른 거래에 속한 분개입니다.");
        }
        this.transaction = transaction;
    }

    // ck_ledger_entries_amount_nonzero · ck_ledger_entries_amount_scale과 이중 방어
    private static BigDecimal requireValidAmount(Currency currency, BigDecimal amount) {
        if (amount == null || amount.signum() == 0) {
            throw new InvalidAmountException("분개 금액은 0일 수 없습니다.");
        }
        currency.requireValidScale(amount);
        return amount;
    }

    public boolean isWallet() { return accountType == AccountType.WALLET; }

    public Long getId() { return id; }
    public LedgerTransaction getTransaction() { return transaction; }
    public AccountType getAccountType() { return accountType; }
    public Long getWalletId() { return walletId; }
    public Currency getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}
