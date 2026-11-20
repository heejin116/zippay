# lb_ip·db_ip는 접속 주소(격리망 VR의 공인 IP). 내부 주소는 *_private_ip
output "lb_ip" {
  description = "lb 접속 주소(static NAT 공인 IP). lb가 앱 계층으로 나갈 때의 출발지이기도 하다"
  value       = module.edge.lb_public_ip
}

output "web_ips" {
  value = module.web.ips
}

output "db_ip" {
  description = "DB 접속 주소(VR 포트포워딩: 5432, SSH는 db_ssh_port)"
  value       = module.edge.db_public_ip
}

output "lb_private_ip" {
  value = module.lb.ips[0]
}

output "db_private_ip" {
  value = module.db.ips[0]
}

# Ansible 인벤토리: terraform output -raw ansible_inventory > ../ansible/inventory.ini
# heredoc + 템플릿 지시자는 들여쓰기·빈 줄이 남아서 줄 목록을 join으로 만든다
# 격리망 VM은 VR 경유: lb는 static NAT IP:22, DB는 포트포워딩 IP:db_ssh_port. private_ip는 격리망 내부 통신용(lb → DB 지표 수집)
output "ansible_inventory" {
  value = join("\n", concat(
    ["[lb]"],
    ["${module.lb.names[0]} ansible_host=${module.edge.lb_public_ip} private_ip=${module.lb.ips[0]}"],
    ["", "[web]"],
    [for i, ip in module.web.ips : "${module.web.names[i]} ansible_host=${ip}"],
    ["", "[db]"],
    ["${module.db.names[0]} ansible_host=${module.edge.db_public_ip} ansible_port=${module.edge.db_ssh_port} private_ip=${module.db.ips[0]}"],
    # k8s_node_ips: K8s worker IP(JSON). Ansible이 DB ufw·pg_hba 5432 허용에 web과 함께 넣는다
    ["", "[all:vars]", "ansible_user=ubuntu", "k8s_node_ips=${jsonencode(var.k8s_node_ips)}", ""],
  ))
}

# ---- K8s 단계 ----
output "k8s_cluster" {
  description = "CKS 클러스터 이름·상태 (k8s_enabled=false면 null). 노드 IP는 provider가 주지 않는다 → kubectl get nodes -o wide"
  value = var.k8s_enabled ? {
    name  = module.k8s[0].name
    state = module.k8s[0].state
  } : null
}
