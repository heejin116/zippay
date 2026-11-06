package com.zippay.charge.service;

import com.zippay.charge.domain.ChargeLimit;
import com.zippay.charge.dto.ChargeResponse;
import com.zippay.common.error.DbConstraints;
import com.zippay.common.idempotency.IdempotencyKey;
import com.zippay.common.idempotency.IdempotencyKeyReusedException;
import com.zippay.common.idempotency.RequestHash;
import com.zippay.common.money.Currency;
import com.zippay.common.money.InvalidAmountException;
import com.zippay.ledger.domain.LedgerTransaction;
import com.zippay.ledger.repository.LedgerTransactionRepository;
import com.zippay.ledger.service.LedgerService;
import com.zippay.ledger.service.Posting;
import com.zippay.wallet.domain.Wallet;
import com.zippay.wallet.exception.WalletNotFoundException;
import com.zippay.wallet.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static com.zippay.ledger.domain.LedgerTransactionType.CHARGE;

/**
 * 충전 유스케이스. 외부(은행) 입금은 즉시 성공한다고 가정한다 — 은행 연동·실패·지연은 범위 밖이며,
 * 그래서 원장에 status가 없다 ("커밋된 거래 = 완료된 거래").
 *
 * <p><b>이 클래스에는 의도적으로 @Transactional을 걸지 않는다.</b>
 * 실제로 돈이 움직이는 트랜잭션은 LedgerService.post() 하나다. 그 트랜잭션에서 멱등성 UNIQUE 위반이 나면
 * Postgres는 트랜잭션을 aborted 상태로 만들어 이후 SELECT도 거부하므로, "먼저 커밋된 거래를 재조회"하는 일은
 * 롤백이 끝난 뒤 새 트랜잭션에서만 할 수 있다. 바깥까지 한 트랜잭션으로 묶으면 그 재조회가 불가능하다.
 * 키 선조회도 트랜잭션 밖에서 해서, 재요청이 지갑 락을 잡지 않고 바로 응답하게 한다.
 */
@Service
public class ChargeService {

    static final String IDEMPOTENCY_CONSTRAINT = "uk_ledger_tx_user_idem_key";

    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;
    private final LedgerTransactionRepository ledgerTransactionRepository;

    public ChargeService(WalletRepository walletRepository,
                         LedgerService ledgerService,
                         LedgerTransactionRepository ledgerTransactionRepository) {
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
    }

    /**
     * 처리 순서:
     *   1. 키 형식 검증, 지갑 소유 확인(남의 지갑은 404), 금액 검증·정규화 → 요청 해시
     *   2. 키 선조회: 이미 있으면 해시 비교 → 같으면 원래 결과 재생, 다르면 422
     *   3. 1회 충전 한도 확인 (선조회 뒤에 둔다 — 한도 정책이 바뀌어도 이미 처리된 요청의 재생은 그대로 되도록)
     *   4. LedgerService.post() — 지갑 락 → 잔액 증가(보유 한도 검사) → 헤더+분개 저장, 한 트랜잭션
     *   5. 4에서 멱등성 UNIQUE 위반 = 같은 키의 동시 요청이 먼저 커밋됨 → 롤백 후 재조회해서 2와 같이 처리
     * 실패한 요청(한도 초과 등)은 롤백되어 키가 남지 않으므로, 같은 키로 재시도하면 다시 실행된다 (효과가 없었으므로 안전).
     */
    public ChargeResult charge(Long userId, Long walletId, String rawIdempotencyKey, BigDecimal requestedAmount) {
        String idempotencyKey = IdempotencyKey.normalize(rawIdempotencyKey);
        Wallet wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new WalletNotFoundException("지갑을 찾을 수 없습니다."));
        Currency currency = wallet.getCurrency();
        BigDecimal amount = normalizeAmount(currency, requestedAmount);
        String requestHash = RequestHash.sha256Hex(canonicalRequest(walletId, currency, amount));

        Optional<LedgerTransaction> existing = findWithEntries(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        ChargeLimit.check(currency, amount);
        try {
            ledgerService.post(userId, idempotencyKey, requestHash, CHARGE, currency, List.of(
                    Posting.wallet(walletId, amount),
                    Posting.external(amount.negate())));
        } catch (DataIntegrityViolationException e) {
            if (!DbConstraints.isViolationOf(e, IDEMPOTENCY_CONSTRAINT)) {
                throw e;
            }
            // 여기는 post()의 트랜잭션이 이미 롤백된 뒤라 새 트랜잭션에서 커밋된 선행 거래가 보인다
            LedgerTransaction winner = findWithEntries(userId, idempotencyKey).orElseThrow(() -> e);
            return replay(winner, requestHash);
        }

        // createdAt은 DB DEFAULT로 채워지므로 커밋 후 다시 읽는다. 재생 경로와 같은 방식으로 응답을 만들기 위해서이기도 하다.
        LedgerTransaction created = findWithEntries(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("방금 커밋한 충전 거래를 찾을 수 없습니다."));
        return new ChargeResult(ChargeResponse.from(created), false);
    }

    private Optional<LedgerTransaction> findWithEntries(Long userId, String idempotencyKey) {
        return ledgerTransactionRepository.findWithEntriesByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    private static ChargeResult replay(LedgerTransaction existing, String requestHash) {
        // 해시에 거래 유형이 들어 있으므로, 같은 키로 만든 송금 거래도 여기서 422가 된다
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException("이미 사용한 Idempotency-Key로 다른 요청을 보낼 수 없습니다.");
        }
        return new ChargeResult(ChargeResponse.from(existing), true);
    }

    /** 0 이하·자릿수 초과를 거부하고 통화 자릿수로 맞춘다 ("10000.00" → "10000"). Wallet·LedgerEntry 검증과 같은 규칙. */
    private static BigDecimal normalizeAmount(Currency currency, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new InvalidAmountException("충전 금액은 0보다 커야 합니다.");
        }
        currency.requireValidScale(amount);
        return amount.setScale(currency.fractionDigits(), RoundingMode.UNNECESSARY);
    }

    /** 해시 대상 정규화 문자열. 형식을 바꾸면 기존 키의 재요청이 전부 422가 되므로 바꾸지 않는다. */
    static String canonicalRequest(Long walletId, Currency currency, BigDecimal normalizedAmount) {
        return "type=" + CHARGE
                + "&walletId=" + walletId
                + "&currency=" + currency.name()
                + "&amount=" + normalizedAmount.toPlainString();
    }

}
