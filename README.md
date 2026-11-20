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
- 인프라: Terraform + Ansible → Solid Cloud(Apache CloudStack 기반 프라이빗 클라우드) · 격리 네트워크 · nginx LB(TLS) · 웹 서버 이중화 · Prometheus·Grafana — [infra/README.md](infra/README.md)

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
- [ ] STEP 13 시나리오 2~3개로 확장, 아키텍처 다이어그램 (배포는 [infra/README.md](infra/README.md) 참고)

**이후 확장**
- Outbox → Kafka 연결
- FDS: 룰 기반 → ML. 동기(결제 경로 내 빠른 룰) vs 비동기(사후 분석) 트레이드오프 설계
- 다통화 환전·외화송금 (KRW·USD·JPY)

## 인프라
Solid Cloud(Apache CloudStack 기반 프라이빗 클라우드)에 Terraform·Ansible로 배포한다 — 격리 네트워크(lb·DB) + 웹 서버 이중화, nginx LB(TLS), DB 데이터·백업 디스크 분리, Prometheus·Grafana 관측, 장애 전환·백업 복구·부하 테스트 측정.
구성도, 접근 제어, 측정 결과, 실행 방법은 **[infra/README.md](infra/README.md)** 참고.

## API (구현된 것)
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/signup` · `/api/auth/login` · `/api/auth/logout` | 회원가입 · 로그인(HttpOnly 쿠키 JWT) · 로그아웃 |
| GET | `/api/users/me` | 내 정보 |
| POST / GET | `/api/wallets` | 지갑 개설(통화당 1개) / 내 지갑 목록 |
| GET | `/api/wallets/{walletId}` | 지갑 조회 (남의 지갑은 404) |
| POST | `/api/wallets/{walletId}/charges` | 충전. `Idempotency-Key`(UUID) 헤더 필수, 본문 `{"amount": "10000"}` |

- 돈을 움직이는 요청은 `Idempotency-Key`(UUID) 헤더 필수 — 같은 키·같은 요청은 원래 결과를 재응답, 같은 키·다른 요청은 422
- 충전 한도·응답 형식·멱등성 규칙 전체는 **[docs/API.md](docs/API.md)**

## 핵심 설계
- **원장이 진실의 원천**: 거래 헤더 + 분개(append-only), 거래당 분개 합 = 0, `wallets.balance`는 같은 트랜잭션에서 갱신하는 캐시
- **동시성**: 지갑 비관적 락(지갑 id 오름차순으로 데드락 방지), 락부터 원장 기록까지 한 트랜잭션
- **멱등성**: UNIQUE(user_id, idempotency_key) + 정규화한 요청의 SHA-256으로 재요청·재사용 판별
- **오류 코드**: 클라이언트가 고칠 수 있는 오류는 4xx, 5xx는 서버 장애 지표로만
- 설계 근거와 처리 순서는 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**
