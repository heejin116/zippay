output "id" {
  value = cloudstack_kubernetes_cluster.this.id
}

output "name" {
  value = cloudstack_kubernetes_cluster.this.name
}

output "state" {
  description = "Running이면 정상"
  value       = cloudstack_kubernetes_cluster.this.state
}

# provider 0.5의 computed 값은 ip_address·state뿐이고, 이 환경(Shared Network)에서는 ip_address가 빈 값으로 온다(11/21 확인).
# worker 노드 IP도 이 리소스로 읽을 수 없다(W11 코드 주석과 같음) → 루트의 k8s_node_ips로 받는다.
