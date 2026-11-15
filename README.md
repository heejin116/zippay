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
- 관측성: Spring Actuator/Micrometer + Prometheus · Grafana · Loki (Docker Compose) — STEP 10 예정
- Frontend: Vite + React + TypeScript
- Test: JUnit + Testcontainers(실제 Postgres)
- CI: GitHub Actions (amd64 이미지 빌드, 커밋 SHA 태그)
- 인프라: Terraform + Ansible → 교내 클라우드(CloudStack) VM 4대 · nginx LB(TLS) · 웹 서버 이중화 — 아래 [인프라](#인프라--교내-클라우드-배포-phase-1) 참고

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
- [ ] STEP 13 시나리오 2~3개로 확장, 아키텍처 다이어그램 (배포는 아래 인프라 Phase 1로 완료)

**이후 확장**
- Outbox → Kafka 연결
- FDS: 룰 기반 → ML. 동기(결제 경로 내 빠른 룰) vs 비동기(사후 분석) 트레이드오프 설계
- 다통화 환전·외화송금 (KRW·USD·JPY)

## 인프라 — 교내 클라우드 배포 (Phase 1)
교내 클라우드(Apache CloudStack)에 Terraform으로 VM 4대를 만들고 Ansible로 구성한다.
설계 기준은 **서비스는 멈추지 않되, 결제 데이터는 정확하게 보존한다**.

```
               클라이언트 (VPN)
                     │ 443 (80 → 443 리다이렉트)
            ┌────────▼────────┐
            │   lb · nginx    │  TLS 종료 · /api/ → 백엔드, / → 프론트
            └────┬───────┬────┘
                 │       │  8080(백엔드) · 8081(프론트)
         ┌───────▼─┐   ┌─▼───────┐
         │  web01  │   │  web02  │  Docker (백엔드 + 프론트)
         └───────┬─┘   └─┬───────┘
                 │       │  5432
            ┌────▼───────▼────┐
            │  db01 · PG 16   │  데이터 디스크 /var/lib/postgresql
            └─────────────────┘  백업 디스크 /backup (cron 15분 pg_dump)
```

| 구성 | 내용 |
|---|---|
| IaC | Terraform 모듈(vm) · lb Small, web×2·db Medium, DB 전용 데이터·백업 디스크, SSH 키만 허용 |
| 구성 관리 | Ansible `site.yml` — ufw, 디스크 포맷·마운트, PostgreSQL 16, Docker, nginx(TLS·경로 분기·재시도) |
| 이미지 | GitHub Actions가 amd64 이미지를 커밋 SHA 태그로 빌드·푸시, Ansible이 그 태그로 기동 |
| 배포 스크립트 | `infra/scripts/deploy.sh` — terraform apply → 인벤토리 생성 → Ansible → LB 경유 헬스체크 |

**계층 간 접근 제어** (ufw + PostgreSQL `pg_hba` 이중 방어)
- SSH는 VPN 대역에서만, 앱 포트(8080·8081)는 LB에서만, DB(5432)는 web VM에서만 허용
- Docker의 공개 포트는 iptables를 직접 조작해 ufw를 우회하므로 web 컨테이너는 host 네트워크로 실행

**장애 대비**
- 웹 서버 이중화: nginx `proxy_next_upstream error timeout http_502 http_503` — 한 대가 응답하지 않으면 다른 web으로 재시도
- 결제 요청(POST)은 nginx 기본값대로 **재시도하지 않는다**(중복 결제 방지). 앱의 `Idempotency-Key`가 2차 방어
- DB는 자원 제약으로 1대. 대신 데이터·백업 디스크를 분리하고 15분마다 `pg_dump -Fc`로 백업(최근 96개 보관)

### 측정 결과 (2026-11)
| 항목 | 방법 | 결과 |
|---|---|---|
| 장애 전환 | LB 경유 0.2초 간격 GET 120초, 15초에 web01 백엔드 중단 → 40초에 재기동 (`failover_test.py`, 응답 서버는 `X-Upstream` 헤더로 기록) | 506건 중 **실패 0건**, 중단 중 요청 110건 모두 web02가 처리, 재기동 **8.6초** 후 web01 복귀 |
| 백업·복구 (RTO) | 합성 데이터(사용자 2,000 · 거래 50,000 · 분개 100,000)를 백업해 새 DB로 복구하고 테이블별 행 수 비교 (`backup_restore_test.sh`) | 백업 0.94초 · **복구 6.44초** · 5개 테이블 모두 일치 |
| 주기 백업 (RPO) | cron `*/15` 실행 기록 확인 | **RPO 상한 15분** |
| 전체 재구축 | `time ./infra/scripts/deploy.sh` (VM 생성 → 구성 → 헬스체크) | **4분 50초** (전체 삭제 21.6초). 디스크까지 새로 만드는 빈 상태 기준 |
| 구성 멱등성 | `ansible-playbook site.yml` 2회 실행 | 1차 3분 6초 · 2차 4대 모두 `changed=0` |

합성 데이터는 `seed_dummy.sql`로 넣고 원장 대사 쿼리(거래별 분개 합 = 0, 지갑별 분개 합 = 잔액)를 통과한 상태에서 측정했다. 원본 로그는 저장소 밖에 보관한다.

### 한계와 다음 단계
- 백업이 DB와 같은 VM에 있어 VM·호스트 장애 시 원본과 함께 잃는다 → **백업을 외부 저장소로 복사**
- 결제 서비스에 RPO 15분은 길다 → **대기 DB로 스트리밍 복제**
- 공유 네트워크라 VM 간 격리가 없다 → 격리 네트워크 + 가상 라우터(방화벽·Egress 규칙) 구조로 전환 (Terraform 생성 테스트 완료)
- Phase 2: Kubernetes 배포 (LB의 경로 분기는 Ingress와 같은 구조로 잡아 둠)

### 실행
```bash
# 준비: infra/terraform/terraform.tfvars, infra/ansible/secrets.yml (각 .example 참고, git 제외)
IMAGE_REPO=<Docker Hub 계정> IMAGE_TAG=<이미지가 있는 커밋 SHA> ./infra/scripts/deploy.sh
DURATION=120 python3 infra/scripts/failover_test.py    # 장애 전환 측정
./infra/scripts/backup_restore_test.sh                  # 백업·복구 측정 (db VM에서)
```

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
