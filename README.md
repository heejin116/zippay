# ZipPay

**결제 코어 + AIOps 장애 대응 에이전트.**
지갑·충전·P2P 송금을 처리하는 거래원장(결제 코어)을 잔액 무결성·동시성·멱등성 중심으로 만들고,
그 위에 메트릭·로그를 근거로 장애 원인과 조치안을 제시하는 AIOps 에이전트를 붙인다.
에이전트는 **제안만 하고, 실행은 사람이 승인한 뒤에만** 한다.
이후 거래 이벤트를 Kafka로 흘려 이상거래탐지(FDS)까지 연결하는 것이 확장 목표.

## 스택
- Backend: Spring Boot 4.1 (Java 21), Spring Data JPA, Spring Security + JWT
- DB: PostgreSQL (스키마는 Flyway 관리)
- 동시성: `@Transactional` + 비관적 락(PESSIMISTIC_WRITE), 지갑 id 오름차순 락
- 멱등성: `Idempotency-Key` 헤더 + 원장 거래 헤더의 UNIQUE(user_id, idempotency_key)
- 금액: BigDecimal + 통화코드(NUMERIC(19,4), HALF_EVEN)
- 이벤트: Transactional Outbox 테이블 (Kafka 연결은 확장 단계)
- 관측성: Spring Actuator/Micrometer + Prometheus · Grafana · Loki (Docker Compose)
- Frontend: Vite + React + TypeScript
- Test: JUnit + Testcontainers(실제 Postgres)
- CI/CD: GitHub Actions · 배포: Ansible → Multipass VM

## 로컬 실행
```bash
# 1) DB 기동
docker compose up -d
# 2) 백엔드 환경변수 — 예시 복사 후 값 채우기 (.env는 git 추적 제외)
cd backend
cp .env.example .env
# 3) 백엔드 실행
set -a; source .env; set +a
./gradlew bootRun
# 4) 프론트
cd frontend && npm install && npm run dev
```
> 최초 `./gradlew` 실행 시 Gradle 배포판을 자동 내려받습니다(인터넷 필요).

## 진행 단계
**1단계 — 결제 코어**
- [x] STEP 0 부트스트랩 (저장소·백엔드·프론트 골격, CI)
- [x] STEP 1 DB/User
- [x] STEP 2 인증 (JWT, HttpOnly 쿠키)
- [x] STEP 3 지갑 (사용자·통화당 1개, 잔액 음수 금지)
- [x] STEP 4 원장 (거래 헤더 + 분개, append-only)
- [x] STEP 5 충전 (멱등성 키)
- [ ] STEP 6 KRW P2P 송금 (지갑 id 오름차순 락, 멱등성 키)
- [ ] STEP 7 Transactional Outbox (테이블·기록까지)
- [ ] STEP 8 동시성 테스트 (수백 건 동시 송금 → 총 잔액 정합성, 결과 수치 기록)
- [ ] STEP 9 refresh 토큰 회전 (해시 저장, 재사용 감지)

**2단계 — 관측성 + AIOps 에이전트 MVP**
- [ ] STEP 10 Actuator/Micrometer + Prometheus·Grafana·Loki (Docker Compose)
- [ ] STEP 11 장애 시나리오 재현 (DB 커넥션 풀 고갈) + 알림 룰
- [ ] STEP 12 에이전트: 알림 → 메트릭·로그 조회 → 원인 후보·조치안 제시 → 사람 승인 후 실행
- [ ] STEP 13 시나리오 2~3개로 확장, Ansible 배포, 아키텍처 다이어그램

**이후 확장**
- Outbox → Kafka 연결
- FDS: 룰 기반 → ML. 동기(결제 경로 내 빠른 룰) vs 비동기(사후 분석) 트레이드오프 설계
- 다통화 환전·외화송금 (KRW·USD·JPY)

## API (구현된 것)
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/signup` · `/api/auth/login` · `/api/auth/logout` | 회원가입 · 로그인(HttpOnly 쿠키 JWT) · 로그아웃 |
| GET | `/api/users/me` | 내 정보 |
| POST / GET | `/api/wallets` | 지갑 개설(통화당 1개) / 내 지갑 목록 |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (남의 지갑은 404) |
| POST | `/api/wallets/{walletId}/charges` | 충전. `Idempotency-Key`(UUID) 헤더 필수, 본문 `{"amount": "10000"}` |

### 충전 규칙
- 통화는 지갑에서 정해지므로 본문에 받지 않는다. 금액은 통화 소수 자릿수(KRW·JPY 0, USD 2)를 넘으면 400.
- 외부(은행) 입금은 **즉시 성공으로 가정**한다. 은행 연동·실패·지연은 범위 밖이라 원장에 status가 없다(커밋된 거래 = 완료된 거래).
- 한도(정책 가정값): 1회 충전 한도 KRW 1,000,000 · USD 1,000.00 · JPY 100,000 (초과 시 400 `CHARGE_LIMIT_EXCEEDED`),
  지갑 보유 한도 KRW 2,000,000 · USD 2,000.00 · JPY 200,000 (초과 시 422 `BALANCE_LIMIT_EXCEEDED`, 송금 입금에도 적용).
  정책 값이라 DB CHECK가 아니라 도메인에서만 검사한다.
- 응답: `201 {transactionId, walletId, currency, amount, balanceAfter, createdAt}`. `balanceAfter`는 그 거래 직후 잔액.

### 멱등성
| 상황 | 응답 |
|---|---|
| 헤더 없음 / UUID 아님 | 400 `IDEMPOTENCY_KEY_REQUIRED` / `INVALID_IDEMPOTENCY_KEY` |
| 처음 보는 키 | 처리 후 201 |
| 같은 키 · 같은 요청 | 원래 결과 그대로 201 + `Idempotent-Replayed: true` (잔액은 한 번만 반영) |
| 같은 키 · 다른 요청 (금액·지갑·거래 유형이 다름) | 422 `IDEMPOTENCY_KEY_REUSED` |

- 키는 사용자별로 유일하다: UNIQUE(user_id, idempotency_key). 대소문자만 다른 키는 같은 키로 본다.
- "같은 요청"은 원본 JSON 바이트가 아니라 정규화한 값(`type=CHARGE&walletId=..&currency=..&amount=..`)의 SHA-256으로 판정한다. 공백·필드 순서·`10000`/`10000.00` 차이는 같은 요청이다.

## 아키텍처 메모
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
- 클라이언트가 고칠 수 있는 오류는 전부 4xx로 낸다. 5xx는 서버 장애 지표로 쓰기 때문에(STEP 10~12) 입력 오류로 오염시키지 않는다.
