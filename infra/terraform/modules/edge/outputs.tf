output "lb_public_ip" {
  value = cloudstack_ipaddress.lb.ip_address
}

output "db_public_ip" {
  value = cloudstack_ipaddress.db.ip_address
}

output "db_ssh_port" {
  value = var.db_ssh_port
}
