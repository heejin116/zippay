package com.zippay.ledger.domain;

import com.zippay.common.money.Currency;
import com.zippay.ledger.exception.InvalidLedgerTransactionException;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 원장 거래 헤더 + 분개 묶음. status 없음 — 커밋된 거래가 곧 완료된 거래.
 * 불변식(분개 통화 = 거래 통화, 분개 합 = 0, 유형별 분개 구성)은 create()에서만 검증하고, 생성 후에는 바뀌지 않는다.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;   // V3 idempotency_key varchar(64)
    private static final Pattern REQUEST_HASH = Pattern.compile("^[0-9a-f]{64}$");   // ck_ledger_tx_request_hash

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 거래를 요청한 사용자 (충전 = 본인, 송금 = 보내는 사람)
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = MAX_IDEMPOTENCY_KEY_LENGTH, updatable = false)
    private String idempotencyKey;

    // DB가 char(64)라 그냥 String이면 validate가 varchar를 기대해 기동 실패 → CHAR로 명시
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LedgerTransactionType type;

    // 거래 통화. 현재는 단일 통화 거래만 있으므로 모든 분개의 통화와 같아야 한다 (fk_ledger_entries_tx_currency)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // PERSIST만 전파: 거래를 저장하면 분개도 함께 INSERT. REMOVE는 원장에 존재하면 안 되므로 ALL을 쓰지 않는다.
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {}

    public static LedgerTransaction create(Long userId,
                                           String idempotencyKey,
                                           String requestHash,
                                           LedgerTransactionType type,
                                           Currency currency,
                                           List<LedgerEntry> entries) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.userId = Objects.requireNonNull(userId, "userId");
        tx.idempotencyKey = requireValidIdempotencyKey(idempotencyKey);
        tx.requestHash = requireValidRequestHash(requestHash);
        tx.type = Objects.requireNonNull(type, "type");
        tx.currency = Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(entries, "entries");

        validateSameCurrency(currency, entries);
        validateBalanced(entries);
        validateShape(type, entries);

        for (LedgerEntry entry : entries) {
            entry.attachTo(tx);
            tx.entries.add(entry);
        }
        return tx;
    }

    /**
     * 분개 통화 = 거래 통화. 통화가 다른 금액은 더할 수 없으므로 아래 합 = 0 검증의 전제 조건이다.
     * 환전 도입 시: 이 검증을 "통화별 Σ(amount) = 0"으로 바꾸고 DB의 fk_ledger_entries_tx_currency를 DROP한다.
     * (통화별 소수 자릿수는 각 분개가 생성 시점에 이미 검증했다)
     */
    private static void validateSameCurrency(Currency currency, List<LedgerEntry> entries) {
        for (LedgerEntry entry : entries) {
            if (entry.getCurrency() != currency) {
                throw new InvalidLedgerTransactionException(
                        "분개 통화(" + entry.getCurrency() + ")가 거래 통화(" + currency + ")와 다릅니다.");
            }
        }
    }

    /** 복식부기 불변식: 거래별 Σ(amount) = 0. 돈은 생기거나 사라지지 않고 계정 사이를 이동만 한다. */
    private static void validateBalanced(List<LedgerEntry> entries) {
        BigDecimal sum = entries.stream()
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // equals는 scale까지 비교하므로(0 vs 0.0000) signum으로 판정
        if (sum.signum() != 0) {
            throw new InvalidLedgerTransactionException("분개 합이 0이 아닙니다: " + sum.toPlainString());
        }
    }

    /**
     * 유형별 분개 구성. 지금은 단순한 2줄 구조만 허용한다 (수수료 등이 생기면 여기를 확장).
     * 금액 크기는 validateBalanced가 보장하므로 여기서는 계정 유형과 부호만 본다.
     */
    private static void validateShape(LedgerTransactionType type, List<LedgerEntry> entries) {
        if (entries.size() != 2) {
            throw new InvalidLedgerTransactionException(type + " 거래는 분개 2줄이어야 합니다: " + entries.size());
        }
        LedgerEntry first = entries.get(0);
        LedgerEntry second = entries.get(1);

        switch (type) {
            case CHARGE -> {
                // 지갑(+) 1줄 + 외부(−) 1줄. 순서는 상관없다.
                LedgerEntry wallet = first.isWallet() ? first : second;
                LedgerEntry external = first.isWallet() ? second : first;
                if (!wallet.isWallet() || external.isWallet() || wallet.getAmount().signum() <= 0) {
                    throw new InvalidLedgerTransactionException("CHARGE는 WALLET(+) 1줄과 EXTERNAL(−) 1줄이어야 합니다.");
                }
            }
            case TRANSFER -> {
                // P2P 송금은 EXTERNAL이 끼지 않는다. 같은 지갑끼리의 이체도 금지.
                if (!first.isWallet() || !second.isWallet()) {
                    throw new InvalidLedgerTransactionException("TRANSFER는 WALLET 분개만 가질 수 있습니다.");
                }
                if (first.getWalletId().equals(second.getWalletId())) {
                    throw new InvalidLedgerTransactionException("같은 지갑으로는 송금할 수 없습니다.");
                }
            }
        }
    }

    private static String requireValidIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidLedgerTransactionException("Idempotency-Key는 1~" + MAX_IDEMPOTENCY_KEY_LENGTH + "자여야 합니다.");
        }
        return key;
    }

    private static String requireValidRequestHash(String hash) {
        if (hash == null || !REQUEST_HASH.matcher(hash).matches()) {
            throw new InvalidLedgerTransactionException("request_hash는 소문자 hex 64자(SHA-256)여야 합니다.");
        }
        return hash;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public LedgerTransactionType getType() { return type; }
    public Currency getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public List<LedgerEntry> getEntries() { return Collections.unmodifiableList(entries); }
}
