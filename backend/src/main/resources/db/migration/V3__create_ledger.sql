-- 원장(거래원장): 잔액의 진실의 원천. append-only — INSERT만 허용하고 UPDATE/DELETE/TRUNCATE는 트리거로 막는다.
-- wallets.balance는 이 원장의 캐시이며, 분개 INSERT와 같은 트랜잭션에서만 갱신된다.

-- ============================================================
-- 거래 헤더: 요청 1건 = 1행. status 없음 — 커밋된 거래가 곧 완료된 거래.
-- ============================================================
CREATE TABLE ledger_transactions (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 거래를 요청한 사용자 (충전 = 본인, 송금 = 보내는 사람)
    user_id          BIGINT        NOT NULL,
    -- 클라이언트가 보낸 Idempotency-Key. 재시도 시 같은 키 → 같은 거래로 판정 (처리 로직은 STEP 5)
    idempotency_key  varchar(64)   NOT NULL,
    -- 정규화한 요청 본문의 SHA-256 hex. 같은 키·다른 본문(422) 판별용
    request_hash     char(64)      NOT NULL,
    type             varchar(20)   NOT NULL,
    -- 거래 통화. 현재는 단일 통화 거래만 존재하므로 모든 분개 통화와 같다 (fk_ledger_entries_tx_currency)
    currency         varchar(3)    NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT fk_ledger_tx_user            FOREIGN KEY (user_id) REFERENCES users (id),
    -- 멱등성의 핵심: 같은 사용자의 같은 키는 한 번만 INSERT된다. 동시 요청은 이 인덱스에서 직렬화된다.
    CONSTRAINT uk_ledger_tx_user_idem_key   UNIQUE (user_id, idempotency_key),
    -- 분개가 (transaction_id, currency) 복합 FK로 참조하기 위한 키
    CONSTRAINT uk_ledger_tx_id_currency     UNIQUE (id, currency),
    CONSTRAINT ck_ledger_tx_idem_key_nonblank CHECK (length(idempotency_key) > 0),
    -- 해시 형식 이중 방어: 소문자 hex 64자만 허용
    CONSTRAINT ck_ledger_tx_request_hash    CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ledger_tx_type            CHECK (type IN ('CHARGE', 'TRANSFER')),
    CONSTRAINT ck_ledger_tx_currency        CHECK (currency IN ('KRW', 'USD', 'JPY'))
);

-- 지갑을 (id, currency) 복합 FK로 참조하기 위한 키.
-- id가 PK라 조합은 당연히 유일하지만, FK는 참조 대상 컬럼 조합에 UNIQUE가 선언돼 있어야 걸 수 있다.
ALTER TABLE wallets ADD CONSTRAINT uk_wallets_id_currency UNIQUE (id, currency);

-- ============================================================
-- 분개: 거래 1건당 2줄 이상. 부호 있는 단일 금액 컬럼(+증가 / −감소), 거래별 합 = 0.
--   WALLET   : ZipPay 지갑. wallet_id·balance_after 필수
--   EXTERNAL : ZipPay 밖(은행) 상대계정. 잔액 캐시·락 없음 → 둘 다 NULL
-- 분개마다 통화를 둔다: 통화별 소수 자릿수를 행 단위 CHECK로 막고, 지갑 통화 일치를 FK로 강제하기 위해.
-- ============================================================
CREATE TABLE ledger_entries (
    id              BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  BIGINT         NOT NULL,
    account_type    varchar(10)    NOT NULL,
    wallet_id       BIGINT,
    currency        varchar(3)     NOT NULL,
    amount          NUMERIC(19,4)  NOT NULL,
    -- 이 분개 반영 직후의 지갑 잔액 (거래내역 화면·대사 시 재계산 없이 사용)
    balance_after   NUMERIC(19,4),

    -- 분개 통화 = 거래 헤더 통화. 환전 도입 시 이 제약만 DROP하고 "통화별 합 = 0"으로 전환한다.
    CONSTRAINT fk_ledger_entries_tx_currency FOREIGN KEY (transaction_id, currency)
        REFERENCES ledger_transactions (id, currency),
    -- 분개 통화 = 지갑 통화 (KRW 지갑에 USD 분개 금지).
    -- EXTERNAL은 wallet_id가 NULL → 기본 MATCH SIMPLE 규칙상 이 FK는 검사되지 않는다.
    CONSTRAINT fk_ledger_entries_wallet_currency FOREIGN KEY (wallet_id, currency)
        REFERENCES wallets (id, currency),
    CONSTRAINT ck_ledger_entries_account_type CHECK (account_type IN ('WALLET', 'EXTERNAL')),
    CONSTRAINT ck_ledger_entries_currency     CHECK (currency IN ('KRW', 'USD', 'JPY')),
    -- 0원 분개 금지
    CONSTRAINT ck_ledger_entries_amount_nonzero CHECK (amount <> 0),
    -- 통화별 소수 자릿수 초과 금지: KRW·JPY 0자리, USD 2자리 (Currency.requireValidScale()과 이중 방어)
    CONSTRAINT ck_ledger_entries_amount_scale CHECK (
        amount = round(amount, CASE currency WHEN 'USD' THEN 2 ELSE 0 END)
    ),
    CONSTRAINT ck_ledger_entries_balance_after_scale CHECK (
        balance_after IS NULL OR balance_after = round(balance_after, CASE currency WHEN 'USD' THEN 2 ELSE 0 END)
    ),
    -- 계정 유형별 컬럼 조합 강제 (LedgerEntry.wallet()/external() 팩터리와 이중 방어)
    CONSTRAINT ck_ledger_entries_account_shape CHECK (
        (account_type = 'WALLET'   AND wallet_id IS NOT NULL AND balance_after IS NOT NULL)
     OR (account_type = 'EXTERNAL' AND wallet_id IS NULL     AND balance_after IS NULL)
    ),
    -- 거래 후 잔액은 음수일 수 없음 (ck_wallets_balance_nonneg와 같은 규칙)
    CONSTRAINT ck_ledger_entries_balance_after_nonneg CHECK (balance_after IS NULL OR balance_after >= 0)
);

-- 거래 → 분개 조회 (FK 컬럼은 Postgres가 자동 인덱싱하지 않는다)
CREATE INDEX ix_ledger_entries_tx            ON ledger_entries (transaction_id);
-- 지갑별 거래내역(최신순)·지갑별 대사 쿼리용. EXTERNAL 행은 제외
CREATE INDEX ix_ledger_entries_wallet_id     ON ledger_entries (wallet_id, id) WHERE wallet_id IS NOT NULL;

-- ============================================================
-- append-only 강제: 원장 행의 수정·삭제는 어떤 경로로든 실패한다.
-- (엔티티 @Immutable과 이중 방어. DB 계정 권한 분리(REVOKE)는 배포 단계에서 추가)
-- ============================================================
CREATE FUNCTION ledger_reject_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '원장은 append-only입니다: % on %는 허용되지 않습니다', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER tg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

-- TRUNCATE는 행 단위 트리거를 거치지 않으므로 문장 단위로 따로 막는다
CREATE TRIGGER tg_ledger_transactions_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER tg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
