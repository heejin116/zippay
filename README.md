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
- [ ] STEP 5 충전 (멱등성 키)
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

## 아키텍처 메모
- 도메인 중심 패키지(auth/wallet/ledger/charge/transfer) + common 기반 계층.
- **원장이 진실의 원천**이고 `wallets.balance`는 같은 트랜잭션에서 갱신되는 캐시. 거래당 분개 합 = 0, Σ(원장) = Σ(잔액)을 테스트로 검증.
- 돈을 움직이는 모든 유스케이스: 거래 헤더 INSERT(멱등성 키) → 지갑 락(id 오름차순) → 잔액 변경 → 분개 → outbox, 전부 한 트랜잭션.
- 같은 멱등성 키의 동시 요청은 유니크 인덱스에서 직렬화된다. 선행 요청이 커밋되면 후행은 기존 결과를 재반환, 롤백되면 후행이 정상 처리.
- 실패한 요청(잔액 부족 등)은 롤백되어 키가 남지 않으므로 재시도 시 재실행된다 (효과가 없었으므로 안전).
