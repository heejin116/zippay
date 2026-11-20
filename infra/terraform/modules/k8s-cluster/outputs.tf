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

# provider 0.5는 클러스터의 API 엔드포인트 주소만 준다. worker 노드 IP는 이 리소스로 읽을 수 없다(W11 코드 주석과 같음).
output "endpoint_ip" {
  description = "클러스터 API 엔드포인트 주소"
  value       = cloudstack_kubernetes_cluster.this.ip_address
}
