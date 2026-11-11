variable "api_url" {
  description = "Solid Cloud(CloudStack) API 엔드포인트"
  type        = string
  default     = "https://dku.kloud.zone/client/api"
}

variable "api_key" {
  description = "API Key (콘솔 프로필 > 사용자 상세 > API 키 생성)"
  type        = string
  sensitive   = true
}

variable "secret_key" {
  description = "Secret Key"
  type        = string
  sensitive   = true
}

variable "student_id" {
  description = "학번. VM 이름 접미사 (예: web01-<학번>)"
  type        = string
}

variable "shared_network_id" {
  description = "Shared Network ID(UUID). 이 provider에는 네트워크 이름 조회 data source가 없어 ID로 받는다"
  type        = string
}

variable "ssh_public_key" {
  description = "VM ubuntu 계정에 넣을 SSH 공개키 (비밀번호 로그인은 끈다)"
  type        = string
}

variable "web_count" {
  description = "web VM 대수 (Phase 1 앱 계층)"
  type        = number
  default     = 2

  validation {
    condition     = var.web_count >= 2 && var.web_count <= 3
    error_message = "web은 이중화를 위해 2대 이상, 쿼터 때문에 3대 이하."
  }
}

variable "root_disk_size" {
  description = "루트 디스크 크기(GB)"
  type        = number
  default     = 20
}

variable "db_disk_offering" {
  description = "DB 데이터·백업 디스크 오퍼링 이름. Phase 1은 스토리지 한도(120GB) 때문에 Small(5GB)"
  type        = string
  default     = "Small"
}
