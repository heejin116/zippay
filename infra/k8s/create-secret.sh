#!/usr/bin/env bash
# zippay backend Secret 생성 (저장소 밖 값 → 클러스터). VM 단계와 같은 DB 계정·JWT 키를 쓴다.
# 값 출처: infra/ansible/secrets.yml (커밋 제외). 다시 실행하면 갱신된다.
# 주의: Secret은 base64일 뿐 암호화가 아니다(etcd 저장 시 암호화는 CKS 설정 범위 밖 — 리포트 한계).
set -euo pipefail
cd "$(dirname "$0")"
NS=zippay
SECRETS=../ansible/secrets.yml

get() { awk -v k="$1" -F': *' '$1==k { sub(/^[^:]*: */, ""); gsub(/^"|"$/, ""); print; exit }' "$SECRETS"; }
DB_PASSWORD="$(get db_password)"
JWT_SECRET="$(get jwt_secret)"
[[ -n "$DB_PASSWORD" && -n "$JWT_SECRET" ]] || { echo "secrets.yml에서 db_password·jwt_secret을 읽지 못했다" >&2; exit 1; }

kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS"
# 값을 명령줄 인자로 넘기지 않으려고 env 파일을 프로세스 치환으로 전달
kubectl -n "$NS" create secret generic zippay-backend \
  --from-env-file=<(printf 'DB_USERNAME=zippay\nDB_PASSWORD=%s\nJWT_SECRET=%s\n' "$DB_PASSWORD" "$JWT_SECRET") \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" get secret zippay-backend -o jsonpath='{.data}' | tr ',' '\n' | sed 's/:.*//'   # 키 이름만 확인
