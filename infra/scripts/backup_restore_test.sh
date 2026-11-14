#!/usr/bin/env bash
# DB 백업·복구 측정 (RTO·RPO)
#   RTO: 백업 파일에서 DB를 다시 세우는 데 걸린 시간 (운영 DB는 건드리지 않고 새 DB에 복구해 잰다)
#   RPO: 마지막 백업 이후 잃을 수 있는 데이터 구간 = 백업 주기(cron) / backup.log로 실제 간격 확인
# 사용 (zippay 루트, VPN 연결): ./infra/scripts/backup_restore_test.sh | tee infra/evidence/$(date +%Y%m%d_%H%M%S)_backup_restore.log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_IP="$(awk '/^\[db\]/{getline; sub(/.*ansible_host=/,""); print $1}' "$ROOT/ansible/inventory.ini")"
echo "DB $DB_IP · $(date -Iseconds)"

ssh -o StrictHostKeyChecking=accept-new "ubuntu@$DB_IP" 'sudo -u postgres bash -s' <<'REMOTE'
set -euo pipefail
cd /tmp
DB=zippay; TEST=zippay_restore_test; DIR=/backup/pg
now() { date +%s.%N; }
diff() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.2f", b-a}'; }   # bc가 없는 이미지 대비

echo "== [1] 지금 백업 1회"
a=$(now); /usr/local/bin/zippay-backup.sh; b=$(now)
LATEST=$(ls -1t "$DIR"/*.dump | head -1)
printf "  파일 %s (%s bytes), 백업 %ss\n" "$(basename "$LATEST")" "$(stat -c %s "$LATEST")" "$(diff "$a" "$b")"

echo "== [2] 장애 가정: 원본 DB를 잃었다고 보고 백업으로 새 DB 복구"
dropdb --if-exists "$TEST"
c=$(now); createdb "$TEST"; pg_restore -d "$TEST" --no-owner "$LATEST"; d=$(now)
RTO=$(diff "$c" "$d")

echo "== [3] 검증: 테이블별 행 수 비교 (원본 vs 복구)"
MISMATCH=0
for t in $(psql -At -d "$DB" -c "select tablename from pg_tables where schemaname='public' order by 1"); do
  x=$(psql -At -d "$DB" -c "select count(*) from \"$t\""); y=$(psql -At -d "$TEST" -c "select count(*) from \"$t\"")
  if [ "$x" = "$y" ]; then flag=OK; else flag=DIFF; MISMATCH=$((MISMATCH+1)); fi
  printf "  %-28s 원본 %6s  복구 %6s  %s\n" "$t" "$x" "$y" "$flag"
done
dropdb "$TEST"

echo "== [4] RPO: 주기 백업 기록 (cron)"
crontab -l | grep zippay-backup || echo "  cron 없음"
tail -n 5 "$DIR/backup.log"

echo
printf "RTO(복구 소요) %ss · 불일치 테이블 %s개 · RPO 상한 = 백업 주기\n" "$RTO" "$MISMATCH"
echo "주의: 현재 데이터가 적어 RTO가 짧다. 데이터량이 늘면 비례해서 늘어난다."
REMOTE
