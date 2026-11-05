package com.zippay.ledger.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 대사(reconciliation) 쿼리: 원장 불변식을 사후에 전수 점검한다. 결과가 비어 있으면 정상.
 * 지금은 테스트용, 이후 STEP 10~12에서 메트릭·AIOps 에이전트의 정합성 점검 도구로 쓴다.
 * 분개 합 0처럼 여러 행에 걸친 규칙은 DB 제약으로 걸 수 없어서, 도메인 검증 + 이 쿼리로 보장한다.
 */
@Repository
@Transactional(readOnly = true)
public class LedgerReconciliationRepository {

    @PersistenceContext
    private EntityManager em;

    /** 불변식 1: 거래별 Σ(amount) = 0. 분개가 2줄 미만인 거래(헤더만 있는 경우 포함)도 위반으로 본다. */
    public List<Long> findUnbalancedTransactionIds() {
        return em.createNativeQuery("""
                SELECT t.id
                FROM ledger_transactions t
                LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                GROUP BY t.id
                HAVING COUNT(e.id) < 2 OR COALESCE(SUM(e.amount), 0) <> 0
                ORDER BY t.id
                """, Long.class).getResultList();
    }

    /** 불변식 2: 지갑별 Σ(WALLET 분개) = wallets.balance. 잔액 캐시가 원장과 어긋난 지갑을 찾는다. */
    public List<Long> findWalletIdsWithBalanceMismatch() {
        return em.createNativeQuery("""
                SELECT w.id
                FROM wallets w
                LEFT JOIN ledger_entries e ON e.wallet_id = w.id
                GROUP BY w.id, w.balance
                HAVING w.balance <> COALESCE(SUM(e.amount), 0)
                ORDER BY w.id
                """, Long.class).getResultList();
    }

    /**
     * 불변식 3: 통화별 Σ(지갑 잔액) = −Σ(EXTERNAL 분개) — ZipPay 안의 총액 = 밖에서 들어온 순액.
     * 통화가 다른 금액은 더할 수 없으므로 통화별로 본다. 어긋난 통화 코드를 돌려준다.
     */
    public List<String> findCurrenciesWithTotalMismatch() {
        return em.createNativeQuery("""
                SELECT c.currency
                FROM (
                    SELECT currency, balance AS amount FROM wallets
                    UNION ALL
                    SELECT currency, amount FROM ledger_entries WHERE account_type = 'EXTERNAL'
                ) c
                GROUP BY c.currency
                HAVING SUM(c.amount) <> 0
                ORDER BY c.currency
                """, String.class).getResultList();
    }
}
