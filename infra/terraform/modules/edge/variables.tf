variable "network_id" {
  description = "격리망 ID"
  type        = string
}

variable "zone" {
  description = "zone 이름"
  type        = string
}

variable "lb_id" {
  description = "lb VM ID (static NAT 대상)"
  type        = string
}

variable "db_id" {
  description = "DB primary VM ID (포트포워딩 대상)"
  type        = string
}

variable "admin_cidrs" {
  description = "관리 SSH 허용 출발지"
  type        = list(string)
}

variable "db_client_cidrs" {
  description = "DB 5432 허용 출발지 (앱 계층)"
  type        = list(string)
}

variable "db_ssh_port" {
  description = "DB SSH 포트포워딩 공개 포트"
  type        = number
}

variable "network_cidr" {
  description = "격리망 대역 (Egress 규칙 출발지)"
  type        = string
}

variable "app_ports" {
  description = "lb가 앱 계층으로 나가는 TCP 포트 (VM 단계: web 8080·8081 / K8s 단계: Ingress NodePort)"
  type        = list(string)
}
