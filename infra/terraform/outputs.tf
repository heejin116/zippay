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
# 격리망 VM은 VR 경유: lb는 static NAT IP:22, DB는 포트포워딩 IP:db_ssh_port
output "ansible_inventory" {
  value = join("\n", concat(
    ["[lb]"],
    ["${module.lb.names[0]} ansible_host=${module.edge.lb_public_ip}"],
    ["", "[web]"],
    [for i, ip in module.web.ips : "${module.web.names[i]} ansible_host=${ip}"],
    ["", "[db]"],
    ["${module.db.names[0]} ansible_host=${module.edge.db_public_ip} ansible_port=${module.edge.db_ssh_port}"],
    ["", "[all:vars]", "ansible_user=ubuntu", ""],
  ))
}
