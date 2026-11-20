#!/usr/bin/env bash
# k6 부하 테스트 실행 + 서버 상태 기록 → infra/evidence/<시각>_k6_<STAGE>_<RPS|stress>.*
# 사용 (zippay 루트, VPN 연결, k6 설치: brew install k6):
#   RATE=50  STAGE=vm ./infra/tests/k6/run.sh               # 목표 판정 (도착률 고정)
#   RATE=100 STAGE=vm ./infra/tests/k6/run.sh
#   SCENARIO=stress STAGE=vm ./infra/tests/k6/run.sh        # 계단식 부하 → 병목 지점 찾기
#   (K8s 단계에서는 STAGE=k8s — 같은 스크립트, 같은 PC에서 실행해 조건을 맞춘다)
# 선택: DURATION=3m USERS=50 STEPS=50,100,200,300,400,500 LOGIN_RATIO=0.05 MAX_LOAD=0.5 MAX_WAIT=300
#
# 실행 순서
#   1) 사전 점검: 모든 서버의 1분 평균 부하가 MAX_LOAD 아래로 내려올 때까지 기다린다
#      → 앞 측정의 부하가 남은 채로 다음 측정을 시작하지 않는다(측정 사이 누적 방지)
#   2) DB 상태 기록(거래 수·DB 크기·연결 수) — 전/후 비교
#   3) 측정 중 서버 상태 기록: 전 서버 vmstat(CPU·메모리·대기), web 컨테이너 CPU, DB 연결 수 (5초 간격)
#   4) k6 실행 → 끝나면 기록 중단, lb nginx 요청별 시간 로그 수집, 요약 출력(summarize.py)
#   (같은 시간대 그래프는 Grafana: http://<lb_ip>:3000 — Node Exporter Full 대시보드)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCENARIO="${SCENARIO:-steady}"
STAGE="${STAGE:-vm}"
if [ "$SCENARIO" = "steady" ]; then
  : "${RATE:?RATE(초당 요청 수)를 지정하세요. 예: RATE=50}"
  LABEL="${RATE}rps"
else
  LABEL="$SCENARIO"
fi
# 로그인을 섞으면 결과 파일 이름에 비율을 붙인다 (예: stress_login5)
if [ -n "${LOGIN_RATIO:-}" ] && [ "${LOGIN_RATIO}" != "0" ]; then
  LABEL="${LABEL}_login$(awk -v r="$LOGIN_RATIO" 'BEGIN { printf "%d", r * 100 }')"
fi
INV="$ROOT/ansible/inventory.ini"
LB_IP="$(terraform -chdir="$ROOT/terraform" output -raw lb_ip)"
OUT="$ROOT/evidence/$(date +%Y%m%d_%H%M%S)_k6_${STAGE}_${LABEL}"
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=5 -o LogLevel=ERROR)

# 인벤토리 → "이름 주소 포트" (격리망 DB는 VR 포트포워딩 포트)
hosts() {
  awk '/ansible_host=/{ h=""; p=22
         for (i = 2; i <= NF; i++) { split($i, kv, "="); if (kv[1] == "ansible_host") h = kv[2]; if (kv[1] == "ansible_port") p = kv[2] }
         print $1, h, p }' "$INV"
}
rssh() { local h=$1 p=$2; shift 2; LC_ALL=C ssh "${SSH_OPTS[@]}" -p "$p" "ubuntu@$h" "$@"; }
# 백그라운드용: exec로 서브셸을 ssh로 바꿔 $!가 ssh 자신을 가리키게 한다
# (함수를 그냥 &로 돌리면 $!는 서브셸이라, kill해도 ssh가 남아 기록이 계속됐다 — 11/19 확인)
rssh_bg() { local h=$1 p=$2; shift 2; LC_ALL=C exec ssh "${SSH_OPTS[@]}" -p "$p" "ubuntu@$h" "$@"; }

db_snapshot() {
  local name h p
  read -r name h p < <(hosts | grep -E '^db')
  rssh "$h" "$p" "sudo -u postgres psql -d zippay -tAF ' | ' -c \"select now()::timestamp(0), 'tx=' || (select count(*) from ledger_transactions), 'size=' || pg_size_pretty(pg_database_size('zippay')), 'conns=' || (select count(*) from pg_stat_activity where datname = 'zippay')\"" 2>/dev/null
}

echo "== [1/4] 사전 점검: 서버 부하가 ${MAX_LOAD:-0.5} 아래가 될 때까지 대기 (최대 ${MAX_WAIT:-300}초)"
deadline=$(( $(date +%s) + ${MAX_WAIT:-300} ))
while :; do
  busy=""; status=""
  while read -r name h p; do
    load=$(rssh "$h" "$p" "cut -d' ' -f1 /proc/loadavg" </dev/null)
    status+="$name=$load "
    awk -v l="$load" -v m="${MAX_LOAD:-0.5}" 'BEGIN { exit !(l >= m) }' && busy+="$name "
  done < <(hosts)
  echo "   $(date +%T) 1분 평균 부하: $status"
  [ -z "$busy" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then echo "   대기 시간 초과 — 부하가 남은 서버: $busy (그대로 진행, 결과에 주의)"; break; fi
  sleep 15
done
{ echo "# 측정 전 상태 $(date -Iseconds)"; echo "load: $status"; echo "db: $(db_snapshot)"; } | tee "$OUT.server.txt"

echo "== [2/4] 서버 상태 기록 시작 (5초 간격)"
pids=()
cleanup() { for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT
while read -r name h p; do
  rssh_bg "$h" "$p" "vmstat -n -t 5" </dev/null > "$OUT.vmstat_${name%%-*}.log" 2>&1 & pids+=($!)
  case "$name" in
    web*) # JVM이 도는 백엔드 컨테이너 CPU·메모리
      rssh_bg "$h" "$p" "while true; do echo \"# \$(date +%T)\"; sudo docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'; sleep 5; done" \
        </dev/null > "$OUT.docker_${name%%-*}.log" 2>&1 & pids+=($!) ;;
    db*) # DB 연결 수 (커넥션 풀이 가득 차는지)
      rssh_bg "$h" "$p" "while true; do echo \"\$(date +%T) \$(sudo -u postgres psql -tAc \"select count(*) filter (where state = 'active') || ' active / ' || count(*) || ' total' from pg_stat_activity where datname = 'zippay'\" 2>/dev/null)\"; sleep 5; done" \
        </dev/null > "$OUT.dbconn.log" 2>&1 & pids+=($!) ;;
  esac
done < <(hosts)

NGINX_FROM="$(date -u +%Y-%m-%dT%H:%M:%S)"   # lb 요청 시간 로그를 이 시각부터 가져온다(서버 시간 UTC)
echo "== [3/4] k6 실행: https://$LB_IP · ${SCENARIO} ${RATE:+${RATE} RPS} → $OUT.{log,json}"
set +e
k6 run \
  -e BASE_URL="https://$LB_IP" -e SCENARIO="$SCENARIO" -e RATE="${RATE:-50}" -e DURATION="${DURATION:-3m}" \
  -e USERS="${USERS:-50}" ${STEPS:+-e STEPS="$STEPS"} -e LOGIN_RATIO="${LOGIN_RATIO:-0}" \
  --summary-export "$OUT.json" \
  "$ROOT/tests/k6/load.js" 2>&1 | tee "$OUT.log"
k6_rc=${PIPESTATUS[0]}
set -e

echo "== [4/4] 기록 중단, 요약"
cleanup; trap - EXIT
# lb nginx 요청별 시간(rt·urt) — k6 시간과 비교해 지연이 어느 구간에서 생기는지 본다
read -r _ lbh lbp < <(hosts | grep -E '^lb')
rssh "$lbh" "$lbp" "sudo awk -v s=$NGINX_FROM 'substr(\$1, 1, 19) >= s' /var/log/nginx/zippay_timing.log 2>/dev/null" > "$OUT.nginx.log" || true
{ echo "# 측정 후 상태 $(date -Iseconds)"; echo "db: $(db_snapshot)"; } | tee -a "$OUT.server.txt"
python3 "$ROOT/tests/k6/summarize.py" "$OUT" | tee "$OUT.summary.txt"
# k6 종료 코드: 0 통과, 99 기준 미달(측정은 유효), 그 외는 실행 오류
exit "$k6_rc"
