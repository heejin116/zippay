-- 백업·복구 측정용 더미 데이터 (API를 거치지 않고 DB에 직접 넣는다). 한 번만 실행 — 원장은 append-only라 지울 수 없다.
-- 사용 (zippay 루트, VPN):
--   ssh ubuntu@<db_ip> 'sudo -u postgres psql -d zippay -v ON_ERROR_STOP=1 -v n=2000 -v k=25 -f -' < infra/scripts/seed_dummy.sql
--   n = 사용자 수(각 KRW 지갑 1개), k = 사용자당 충전 건수(1만 원씩) → 거래 n*k건, 분개 2*n*k건
-- 원장 규칙을 그대로 지킨다: 거래별 분개 합 = 0, 지갑 잔액 = 지갑 분개 합, balance_after = 누적 잔액
\timing on
BEGIN;

INSERT INTO users (email, password_hash, role)
SELECT 'seed' || g || '@zippay.test',
       '$2a$10$seedseedseedseedseedseOseedseedseedseedseedseedseedse',  -- 로그인 불가 더미 해시
       'USER'
FROM generate_series(1, :n) g;

INSERT INTO wallets (user_id, currency, balance)
SELECT id, 'KRW', :k * 10000
FROM users WHERE email LIKE 'seed%@zippay.test';

WITH s AS (
    SELECT u.id AS uid, w.id AS wid, g,
           md5('seed-' || u.id || '-' || g)::uuid::text AS idem
    FROM users u
    JOIN wallets w ON w.user_id = u.id AND w.currency = 'KRW'
    CROSS JOIN generate_series(1, :k) g
    WHERE u.email LIKE 'seed%@zippay.test'
), t AS (
    INSERT INTO ledger_transactions (user_id, idempotency_key, request_hash, type, currency)
    SELECT uid, idem,
           encode(sha256(convert_to('type=CHARGE&walletId=' || wid || '&currency=KRW&amount=10000&seq=' || g, 'UTF8')), 'hex'),
           'CHARGE', 'KRW'
    FROM s
    RETURNING id, user_id, idempotency_key
)
INSERT INTO ledger_entries (transaction_id, account_type, wallet_id, currency, amount, balance_after)
SELECT t.id, 'WALLET', s.wid, 'KRW', 10000, s.g * 10000
FROM t JOIN s ON s.uid = t.user_id AND s.idem = t.idempotency_key
UNION ALL
SELECT t.id, 'EXTERNAL', NULL, 'KRW', -10000, NULL
FROM t;

COMMIT;

-- 대사(검증): 셋 다 0이어야 한다
SELECT
  (SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING sum(amount) <> 0) x) AS 합0아닌거래,
  (SELECT count(*) FROM wallets w WHERE w.balance <> COALESCE((SELECT sum(amount) FROM ledger_entries e WHERE e.wallet_id = w.id), 0)) AS 잔액불일치지갑,
  (SELECT COALESCE(sum(amount),0) FROM ledger_entries) AS 전체분개합;

SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM wallets) AS wallets,
       (SELECT count(*) FROM ledger_transactions) AS transactions, (SELECT count(*) FROM ledger_entries) AS entries,
       pg_size_pretty(pg_database_size('zippay')) AS db_size;
