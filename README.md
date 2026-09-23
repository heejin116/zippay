# ZipPay

간편페이/거래원장 서비스 — 다통화 지갑, P2P 송금, 실시간(공시환율 기반) 환전·외화송금.
핵심은 **잔액 무결성·동시성·멱등성**을 갖춘 거래원장.

## 스택
- Backend: Spring Boot 3.3 (Java 21), Spring Data JPA, Spring Security + JWT
- DB: PostgreSQL (스키마는 Flyway 관리)
- 동시성: `@Transactional` + 비관적 락(PESSIMISTIC_WRITE), 지갑 id 오름차순 락
- 금액: BigDecimal + 통화코드(NUMERIC(19,4), HALF_EVEN)
- 환율: 한국수출입은행 Open API 스냅샷 + 견적 TTL 락
- Frontend: Vite + React + TypeScript
- Test: JUnit + Testcontainers(실제 Postgres) + Playwright(E2E)
- CI/CD: GitHub Actions · 배포: Vercel(FE) / Railway·Render(BE) / Neon(DB)

## 로컬 실행
```bash
# 1) DB 기동
docker compose up -d
# 2) 백엔드 (dev 프로파일 기본)
cd backend && ./gradlew bootRun
# 3) 프론트
cd frontend && npm install && npm run dev
```
> 최초 `./gradlew` 실행 시 Gradle 배포판을 자동 내려받습니다(인터넷 필요).

## 진행 단계
**M1 — 인증·지갑·충전**
- [ ] STEP 0 부트스트랩 (저장소·백엔드·프론트 골격, CI)
- [ ] STEP 1 DB/User
- [ ] STEP 2 인증
- [ ] STEP 3 지갑
- [ ] STEP 4 충전

**M2 — KRW P2P 송금·원장·배포**
- [ ] STEP 5 원장
- [ ] STEP 6 송금
- [ ] STEP 7 동시성 테스트
- [ ] STEP 8 배포/CI

**M3 — 다통화 환전·외화송금**
- [ ] STEP 9+ 환전·외화송금 (KRW·USD·JPY)

## 아키텍처 메모
도메인 중심 패키지(auth/wallet/charge/transfer/ledger/exchange) + common 기반 계층.
돈을 움직이는 모든 유스케이스는 wallet의 비관적 락을 통과하고 ledger에 append-only로 기록된다.
통화별 무결성: Σ(입금−출금) = Σ(balance), 환전 손익은 하우스 계정으로 귀속.
