#!/usr/bin/env bash
# K8s 단계 ②·⑤: 클러스터 기본 구성 (metrics-server, ingress-nginx, node-exporter)
# 실행 위치: 본인 Mac (VPN 연결, KUBECONFIG=~/.kube/zippay-k8s.conf). 여러 번 실행해도 같은 결과(helm upgrade --install).
# 버전은 W12 실습에서 검증한 값으로 고정한다.
set -euo pipefail
cd "$(dirname "$0")"

METRICS_SERVER_CHART_VERSION=3.14.0
INGRESS_NGINX_CHART_VERSION=4.15.1
NODE_EXPORTER_CHART_VERSION=4.53.1   # node_exporter 1.11.1

kubectl cluster-info >/dev/null # kubeconfig·VPN 확인

helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ --force-update >/dev/null
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx --force-update >/dev/null
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update >/dev/null

echo "== metrics-server ${METRICS_SERVER_CHART_VERSION}"
helm upgrade --install metrics-server metrics-server/metrics-server \
  -n kube-system --version "${METRICS_SERVER_CHART_VERSION}" \
  -f values/metrics-server.yaml --wait --timeout 5m

echo "== ingress-nginx ${INGRESS_NGINX_CHART_VERSION}"
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx --create-namespace --version "${INGRESS_NGINX_CHART_VERSION}" \
  -f values/ingress-nginx.yaml --wait --timeout 5m

echo "== node-exporter ${NODE_EXPORTER_CHART_VERSION} (worker만, lb Prometheus가 수집)"
helm upgrade --install node-exporter prometheus-community/prometheus-node-exporter \
  -n monitoring --create-namespace --version "${NODE_EXPORTER_CHART_VERSION}" \
  -f values/node-exporter.yaml --wait --timeout 5m

echo "== 확인"
kubectl get pods -n ingress-nginx -o wide
kubectl get svc -n ingress-nginx ingress-nginx-controller
kubectl get pods -n monitoring -o wide
kubectl top nodes || echo "(metrics-server 지표 수집까지 1분 정도 걸릴 수 있다)"
