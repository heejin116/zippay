variable "name" {
  description = "네트워크 이름"
  type        = string
}

variable "cidr" {
  description = "격리망 대역"
  type        = string
}

variable "zone" {
  description = "zone 이름"
  type        = string
}

variable "network_offering" {
  description = "격리망 network offering name"
  type        = string
}
