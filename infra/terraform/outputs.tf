output "lb_ip" {
  value = module.lb.ips[0]
}

output "web_ips" {
  value = module.web.ips
}

output "db_ip" {
  value = module.db.ips[0]
}

# Ansible 인벤토리: terraform output -raw ansible_inventory > ../ansible/inventory.ini
# heredoc + 템플릿 지시자는 들여쓰기·빈 줄이 남아서 줄 목록을 join으로 만든다
output "ansible_inventory" {
  value = join("\n", concat(
    ["[lb]"],
    [for i, ip in module.lb.ips : "${module.lb.names[i]} ansible_host=${ip}"],
    ["", "[web]"],
    [for i, ip in module.web.ips : "${module.web.names[i]} ansible_host=${ip}"],
    ["", "[db]"],
    [for i, ip in module.db.ips : "${module.db.names[i]} ansible_host=${ip}"],
    ["", "[all:vars]", "ansible_user=ubuntu", ""],
  ))
}
