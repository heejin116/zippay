#!/usr/bin/env bash
# zippay 앱 계층 배포·재배포 (K8s 단계). VM 단계 deploy.sh와 비교할 재배포 시간을 잰다.
# 사용: IMAGE_REPO=<Docker Hub 계정> IMAGE_TAG=<커밋 SHA> ./infra/k8s/deploy-app.sh
# 롤백: helm -n zippay rollback zippay  (직전 revision)
set -euo pipefail
cd "$(dirname "$0")"
: "${IMAGE_REPO:?IMAGE_REPO(Docker Hub 계정)를 지정하세요}"
IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse HEAD)}"

DB_IP="$(terraform -chdir=../terraform output -raw db_ip)"   # VR 포트포워딩 주소(5432)
kubectl -n zippay get secret zippay-backend >/dev/null || { echo "Secret이 없다: ./create-secret.sh 먼저" >&2; exit 1; }

start=$(date +%s)
helm upgrade --install zippay ../helm/zippay -n zippay \
  --set image.repository="$IMAGE_REPO" \
  --set image.tag="$IMAGE_TAG" \
  --set backend.dbUrl="jdbc:postgresql://${DB_IP}:5432/zippay" \
  --wait --timeout 5m
echo "배포 완료: $(( $(date +%s) - start ))초 (모든 파드 Ready까지)"
kubectl -n zippay get pods -o wide
