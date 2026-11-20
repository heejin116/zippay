# ZipPay 인프라 — Solid Cloud(Apache CloudStack) 배포

Solid Cloud(Apache CloudStack 기반 프라이빗 클라우드, VPN으로만 접속)에 Terraform으로 자원을 만들고 Ansible로 구성한다.
설계 기준은 **서비스는 멈추지 않되, 결제 데이터는 정확하게 보존한다**(1순위 가용성, 2순위 보안·규제).

## 구성 (현재: VM 단계)
```
                        클라이언트 (VPN)
                              │ 443 (80 → 443 리다이렉트)
 ┌─ 격리 네트워크 ────────────▼─────────────────────────────────────┐
 │  가상 라우터(VR): Ingress 방화벽 · static NAT · 포트포워딩 · Egress    │  ← 경계 1 (Terraform)
 │                                                                  │
 │   lb · nginx     TLS 종료 · /api/ → 백엔드, / → 프론트              │
 │                  + Prometheus · Grafana · 알림 룰(up == 0)           │
 │   db01 · PG 16   데이터 디스크 /var/lib/postgresql                   │
 │                  백업 디스크 /backup (cron 15분 pg_dump, 96개 보관)    │
 └──────────┬──────────────────────────────▲────────────────────────┘
   8080·8081│ (Egress)                5432 │ (VR 포트포워딩, web IP만)
 ┌─ Shared Network ─▼──────────────────────┴───┐
 │   web01 · web02   Docker (백엔드 + 프론트)     │  ← 경계 2: 모든 VM ufw (Ansible)
 └─────────────────────────────────────────────┘
```
- lb·DB는 격리 네트워크, 앱 계층(web)은 Shared Network. 쿠버네티스(CKS)는 이 환경에서 격리 네트워크에 만들 수 없어(노드와 망의 MTU 불일치) 앱 계층은 처음부터 Shared에 두고, VM 단계와 K8s 단계를 같은 경로(LB → VR → 앱 → VR → DB)로 비교한다.
- lb는 static NAT: 들어오는 443·80과 lb가 나가는 트래픽이 같은 공인 IP를 써서, web 방화벽에서 lb 출발지를 IP 하나로 좁힌다.

| 구성 | 내용 |
|---|---|
| IaC | Terraform 모듈 `vm` · `network`(격리망) · `edge`(공인 IP·방화벽·static NAT·포트포워딩·Egress). lb·DB Small, web×2 Medium. DB 데이터·백업 디스크 분리(VM을 다시 만들어도 디스크는 재연결). SSH 키만 허용, 관리 SSH를 `0.0.0.0/0`에 열지 못하게 변수 검증 |
| 구성 관리 | Ansible `site.yml` — ufw, 디스크 포맷·마운트, PostgreSQL 16(재연결한 데이터 디스크에 설정 복구), Docker, nginx(TLS·경로 분기·재시도·요청 시간 로그), node_exporter, Prometheus, Grafana |
| 이미지 | GitHub Actions가 amd64 이미지를 커밋 SHA 태그로 빌드·푸시, Ansible이 그 태그로 기동 |
| 배포 스크립트 | `scripts/deploy.sh` — terraform apply → 인벤토리 생성 → Ansible → LB 경유 헬스체크 |

## 접근 제어 — 경계 두 겹
- **경계 1 (VR, Terraform)**: 443·80만 lb로. 관리 SSH(lb 22, DB 2211)와 Grafana(3000)·Prometheus(9090)는 VPN 출발지(10.0.0.10/32)만. DB 5432는 web IP에서만 포트포워딩. Egress는 기본 전면 차단 → DNS·HTTP·HTTPS, lb→web 앱 포트·node_exporter 포트만 허용.
- **경계 2 (ufw, Ansible)**: 기본 거부. 앱 포트(8080·8081)는 lb에서만, DB 5432는 web에서만(PostgreSQL `pg_hba`와 이중 방어), node_exporter 9100은 Prometheus(lb)에서만.
- Docker 공개 포트는 iptables를 직접 조작해 ufw를 우회하므로 web 컨테이너는 host 네트워크로 실행.

## 장애 대비
- 웹 서버 이중화: nginx `proxy_next_upstream error timeout http_502 http_503` — 한 대가 응답하지 않으면 다른 web으로 재시도.
- 결제 요청(POST)은 nginx 기본값대로 **재시도하지 않는다**(중복 결제 방지). 앱의 `Idempotency-Key`가 2차 방어.
- DB: 데이터·백업 디스크 분리, 15분마다 `pg_dump -Fc`(RPO 상한 15분). DB standby(스트리밍 복제)는 다음 단계.

## 관측 (수업 6주차 구성)
- node_exporter(:9100)를 모든 VM에, Prometheus(5초 수집, 보관 30일)·Grafana·알림 룰은 lb에. Grafana 데이터소스와 Node Exporter Full 대시보드는 provisioning으로 — 재구축 후에도 자동 복구.
- lb nginx `zippay_timing` 로그: 요청별 `rt`(lb 처리 시간)·`urt`(lb → 백엔드 응답 시간) — 부하 테스트에서 지연을 구간별로 나누는 데 쓴다.
- 트레이드오프: 관측 스택이 lb에 있어 lb가 멈추면 관측도 잃는다. 대시보드 조회도 lb에 부하를 준다(측정 중에는 대시보드를 닫는다).

## 측정 결과
### Phase 1 (2026-11-11~14 · 모든 VM Shared Network · DB Medium)
| 항목 | 방법 | 결과 |
|---|---|---|
| 장애 전환 | LB 경유 0.2초 간격 GET 120초, 15초에 web01 백엔드 중단 → 40초에 재기동 (`failover_test.py`, 응답 서버는 `X-Upstream` 헤더) | 506건 중 **실패 0건**, 중단 중 요청 110건 모두 web02가 처리, 재기동 **8.6초** 후 web01 복귀 |
| 백업·복구 (RTO) | 합성 데이터(사용자 2,000 · 거래 50,000 · 분개 100,000)를 백업해 새 DB로 복구하고 테이블별 행 수 비교 (`backup_restore_test.sh`) | 백업 0.94초 · **복구 6.44초** · 5개 테이블 모두 일치 |
| 주기 백업 (RPO) | cron `*/15` 실행 기록 확인 | **RPO 상한 15분** |
| 전체 재구축 | `time ./infra/scripts/deploy.sh` (VM 생성 → 구성 → 헬스체크) | **4분 50초** (전체 삭제 21.6초). 디스크까지 새로 만드는 빈 상태 기준 |
| 구성 멱등성 | `ansible-playbook site.yml` 2회 실행 | 1차 3분 6초 · 2차 4대 모두 `changed=0` |

합성 데이터는 `scripts/seed_dummy.sql`로 넣고 원장 대사 쿼리(거래별 분개 합 = 0, 지갑별 분개 합 = 잔액)를 통과한 상태에서 측정했다.

### VM 단계 부하 테스트 (2026-11-19~20 · 격리망 lb·DB + Shared web · DB Small)
요청 구성: 지갑 조회 90% · 충전 10%(1,000원, 요청마다 새 멱등성 키), 도착률 고정. 목표: 수요 50 RPS에서 클라이언트 p95 ≤ 300ms, 서버 구간(lb `rt`) p95 ≤ 100ms, 에러율 조회 < 1%·충전 < 0.1%, 용량은 수요 10배(500 RPS).

| 항목 | 결과 |
|---|---|
| 50 RPS (3분, 2회) | 에러 0% · 클라이언트 p95 107 / 103ms |
| 100 RPS (3분) | 에러 0% · 클라이언트 p95 113ms · p99 287ms |
| 계단식 50→400 RPS | 400 RPS까지 에러 0% · 클라이언트 p95 107~118ms · **서버 구간(lb rt) p95 24~34ms**(충전 37ms), 부하와 무관하게 평평 · 400 RPS에서 CPU DB 22% · web 14~17% · lb 10% |

- 클라이언트가 본 p95의 약 3/4(≈80ms)은 lb 앞단(부하 발생 PC·VPN·연결)에서 생기고, 서버 구간은 약 30ms다.
- 한계: 부하 발생기가 개인 PC(VPN 경유)라 클라이언트 수치에 PC 상태·VPN 편차가 섞인다. 측정한 경로는 조회·충전뿐이고 외부 은행 연동이 없으며(입금은 즉시 성공 가정) 데이터가 작아(약 50MB) 전부 메모리에 올라간다 — "현재 구현 범위" 기준 결과다.

## 한계와 다음 단계
- 결제 서비스에 RPO 15분은 길다 → **DB standby(스트리밍 복제)** 추가, 수동 승격으로 RTO 측정
- 백업이 DB와 같은 VM에 있어 VM·호스트 장애 시 원본과 함께 잃는다 → 외부 저장소로 복사
- lb가 단일 입구이자 관측 서버 → 실서비스라면 LB 이중화·관측 서버 분리
- **Kubernetes 단계**: 앱 계층을 CKS(Shared Network)로 옮기고 같은 조건으로 VM과 비교 (LB의 경로 분기는 Ingress와 같은 구조로 잡아 둠)
- 로그인을 섞은 부하 시나리오로 병목(BCrypt)·개선 전후 비교

## 실행
```bash
# 준비: infra/terraform/terraform.tfvars, infra/ansible/secrets.yml (각 .example 참고, git 제외), VPN 연결
IMAGE_REPO=<Docker Hub 계정> IMAGE_TAG=<이미지가 있는 커밋 SHA> ./infra/scripts/deploy.sh

RATE=50 STAGE=vm ./infra/tests/k6/run.sh                    # 부하 테스트(목표 판정) — 결과는 infra/evidence/
SCENARIO=stress STAGE=vm ./infra/tests/k6/run.sh            # 계단식 부하(병목 찾기), LOGIN_RATIO=0.05로 로그인 섞기
DURATION=120 python3 infra/scripts/failover_test.py          # 장애 전환 측정
./infra/scripts/backup_restore_test.sh                       # 백업·복구 측정
```
`run.sh`는 측정 전에 모든 서버의 부하가 내려올 때까지 기다리고(앞 측정의 영향 방지), 측정 중 서버 상태(vmstat·컨테이너 CPU·DB 연결 수)와 lb 요청 시간 로그를 함께 남긴다. Grafana는 `http://<lb 공인 IP>:3000`.

## 디렉터리
```
infra/
├─ terraform/        # 모듈 vm · network · edge, terraform.tfvars.example
├─ ansible/          # site.yml, group_vars, templates(nginx·Prometheus·백엔드 env), files(알림 룰)
├─ compose/          # 로컬 전체 실행 · web VM용 compose
├─ scripts/          # deploy.sh, failover_test.py, backup_restore_test.sh, seed_dummy.sql
├─ tests/k6/         # load.js(부하 시나리오), run.sh(실행·서버 상태 기록), summarize.py(요약)
└─ evidence/         # 측정 결과·캡처 (git 제외, 마스킹 후 제출)
```
