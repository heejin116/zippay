# ZipPay 아키텍처 메모

결제 코어의 설계 결정과 근거. API 규칙은 [API.md](API.md), 인프라는 [infra/README.md](../infra/README.md).

- 도메인 중심 패키지(auth/wallet/ledger/charge, 예정: transfer) + common 기반 계층(config, error, idempotency, money, security).
- **원장이 진실의 원천**이고 `wallets.balance`는 같은 트랜잭션에서 갱신되는 캐시. 거래당 분개 합 = 0, Σ(원장) = Σ(잔액)을 대사 쿼리와 테스트로 검증.
- 돈을 움직이는 유스케이스의 순서:
  1. (트랜잭션 밖) 멱등성 키 선조회 → 있으면 요청 해시를 비교해 재응답 또는 422
  2. (한 트랜잭션, `LedgerService.post`) 지갑 락(`PESSIMISTIC_WRITE`, 지갑 id 오름차순) → 잔액 변경 → 거래 헤더+분개를 한 번에 저장(UNIQUE가 중복의 최종 방어) → outbox(STEP 7)
  3. (트랜잭션 밖) 2에서 멱등성 UNIQUE 위반이면 롤백 후 새로 조회해 1과 같이 처리
- 헤더를 먼저 INSERT하지 않는 이유: `balance_after`는 락·잔액 변경 뒤에야 정해지고, 헤더+분개를 한 묶음으로 검증하기 위해서.
- 바깥 계층(`ChargeService`)에 `@Transactional`이 없는 이유: Postgres는 UNIQUE 위반 후 트랜잭션을 aborted 상태로 만들어 이후 SELECT도 거부한다. 그래서 먼저 커밋된 거래를 다시 조회하는 일은 롤백 뒤 새 트랜잭션에서만 가능하다.
- 같은 키로 동시에 들어온 요청은 같은 지갑이면 지갑 락에서, 다른 지갑이면 UNIQUE 인덱스에서 직렬화된다. 선행 요청이 커밋되면 후행은 UNIQUE 위반 → 기존 결과 재반환, 선행이 롤백되면 후행이 정상 처리. (`ChargeConcurrencyTest`로 검증)
- 실패한 요청(한도 초과·잔액 부족 등)은 롤백되어 키가 남지 않으므로 재시도 시 재실행된다 (효과가 없었으므로 안전).
