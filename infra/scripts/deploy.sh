#!/usr/bin/env bash
# 전체 배포: terraform apply → 인벤토리 생성 → Ansible 구성 → LB 경유 헬스체크
# 사용: IMAGE_REPO=<dockerhub 계정> [IMAGE_TAG=<SHA>] ./infra/scripts/deploy.sh
# 시간 측정(재구축 시간): time ./infra/scripts/deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${IMAGE_REPO:?IMAGE_REPO(Docker Hub 계정)를 지정하세요}"
export IMAGE_REPO
export IMAGE_TAG="${IMAGE_TAG:-$(git -C "$ROOT" rev-parse HEAD)}"   # 기본: 현재 커밋 (Actions가 푸시한 태그와 같아야 한다)

echo "== [1/4] terraform apply"
terraform -chdir="$ROOT/terraform" apply -auto-approve

echo "== [2/4] Ansible 인벤토리 생성"
terraform -chdir="$ROOT/terraform" output -raw ansible_inventory > "$ROOT/ansible/inventory.ini"
LB_IP="$(terraform -chdir="$ROOT/terraform" output -raw lb_ip)"

echo "== [3/4] SSH 열릴 때까지 대기 후 Ansible"
cd "$ROOT/ansible"
ansible all -m wait_for_connection -a "timeout=300" >/dev/null
ansible-playbook site.yml

echo "== [4/4] LB 경유 헬스체크 (자체 서명이라 -k)"
for i in $(seq 1 30); do
  if curl -fsk "https://$LB_IP/" >/dev/null && \
     curl -sk -o /dev/null -w '%{http_code}' "https://$LB_IP/api/users/me" | grep -q '^401$'; then
    echo "OK: https://$LB_IP (프론트 200, API 401 = 백엔드 응답)"
    exit 0
  fi
  sleep 5
done
echo "헬스체크 실패" >&2
exit 1
