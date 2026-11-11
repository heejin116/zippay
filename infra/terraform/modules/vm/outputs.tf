output "names" {
  value = cloudstack_instance.this[*].name
}

output "ips" {
  value = cloudstack_instance.this[*].ip_address
}

output "ids" {
  value = cloudstack_instance.this[*].id
}
