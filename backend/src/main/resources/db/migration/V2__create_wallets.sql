-- 지갑: (user_id, currency)당 1행. 잔액은 원장의 캐시이며 같은 트랜잭션에서만 갱신된다.
CREATE TABLE wallets (
    id          BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT         NOT NULL,
    currency    varchar(3)     NOT NULL,
    balance     NUMERIC(19,4)  NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT fk_wallets_user             FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_wallets_user_currency    UNIQUE (user_id, currency),
    CONSTRAINT ck_wallets_currency         CHECK (currency IN ('KRW', 'USD', 'JPY')),
    -- 잔액 음수 금지 (도메인 decrease()와 이중 방어)
    CONSTRAINT ck_wallets_balance_nonneg   CHECK (balance >= 0),
    -- 통화별 소수 자릿수 초과 금지: KRW·JPY 0자리, USD 2자리 (Currency.requireValidScale()과 이중 방어)
    CONSTRAINT ck_wallets_balance_scale    CHECK (
        balance = round(balance, CASE currency WHEN 'USD' THEN 2 ELSE 0 END)
    )
);
