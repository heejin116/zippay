variable "name" {
  description = "클러스터 이름. 노드 VM은 <name>-control-*, <name>-node-* 로 만들어진다"
  type        = string
}

variable "description" {
  description = "클러스터 설명"
  type        = string
  default     = "zippay app tier (Terraform)"
}

variable "zone" {
  description = "zone 이름"
  type        = string
}

variable "kubernetes_version" {
  description = "콘솔에 등록된 Kubernetes 버전 이름 (Calico 포함, W11과 같은 값)"
  type        = string
}

variable "service_offering" {
  description = "노드 컴퓨트 오퍼링 이름. CKS 노드는 Medium 고정"
  type        = string
}

variable "control_count" {
  description = "control 노드 수. HA는 3대(etcd 쿼럼)가 필요해 자원 한도로 1대 — 감수한 SPOF"
  type        = number
}

variable "worker_count" {
  description = "worker 노드 수"
  type        = number
}

variable "network_id" {
  description = "Shared Network ID (격리망 불가)"
  type        = string

  validation {
    condition     = length(trimspace(var.network_id)) > 0
    error_message = "network_id를 비우면 격리망이 새로 만들어져 Starting에서 멈춘다. Shared Network ID를 넣는다."
  }
}

variable "node_root_disk_gb" {
  description = "노드 루트 디스크(GB). provider 기본값은 8이라 명시한다"
  type        = number
}

variable "keypair" {
  description = "노드에 넣을 SSH 키페어 이름 (cloud 계정으로 접속)"
  type        = string
}
