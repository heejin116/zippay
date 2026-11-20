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

variable "isolated_cidr" {
  description = "격리망(lb·DB) 대역"
  type        = string
  default     = "192.168.10.0/24"
}

variable "isolated_network_offering" {
  description = "격리망 network offering name (probe에서 동작 확인)"
  type        = string
  default     = "DefaultIsolatedNetworkOfferingWithSourceNatService"
}

variable "admin_cidrs" {
  description = "관리 SSH 허용 출발지. VPN 사용자는 VR에서 10.0.0.10으로 보인다(W1 강사 실측·Ansible 출력 일치)"
  type        = list(string)
  default     = ["10.0.0.10/32"]

  validation {
    condition     = !contains(var.admin_cidrs, "0.0.0.0/0")
    error_message = "관리 SSH를 0.0.0.0/0에 열지 않는다."
  }
}

variable "app_ports" {
  description = "lb → 앱 계층 TCP 포트 (Egress 허용). VM 단계: backend 8080, frontend 8081"
  type        = list(string)
  default     = ["8080", "8081"]
}

variable "db_ssh_port" {
  description = "DB SSH 포트포워딩 공개 포트 (DESIGN.md: 2211 → db-primary:22)"
  type        = number
  default     = 2211
}

variable "monitoring_ports" {
  description = "lb(Prometheus) → 격리망 밖 수집 포트 (Egress 허용). web node_exporter 9100"
  type        = list(string)
  default     = ["9100"]
}

# ---- K8s 단계 (modules/k8s-cluster) ----
variable "k8s_enabled" {
  description = "CKS 클러스터 생성 여부. VM 단계와 동시에 띄워 같은 lb·DB로 비교한 뒤 web VM을 지운다"
  type        = bool
  default     = false
}

variable "k8s_version" {
  description = "CKS Kubernetes 버전 이름 (W11과 같은 값)"
  type        = string
  default     = "Kubernetes_1.32.5_with_Calico"
}

variable "k8s_worker_count" {
  description = "worker 노드 수. 2대 = web VM 2대와 같은 앱 자원(4 vCPU / 8GiB). 3대는 N+1 실측용"
  type        = number
  default     = 2

  validation {
    condition     = var.k8s_worker_count >= 2 && var.k8s_worker_count <= 3
    error_message = "worker는 이중화를 위해 2대 이상, 자원 한도 때문에 3대 이하."
  }
}

variable "k8s_node_root_disk_gb" {
  description = "CKS 노드 루트 디스크(GB)"
  type        = number
  default     = 20
}

variable "k8s_node_ips" {
  description = <<-EOT
    worker 노드 IP 목록. DB 5432 허용 출발지에 더한다.
    provider 0.5는 worker IP를 출력하지 않아 클러스터 생성 후 채우고 한 번 더 apply한다:
    kubectl get nodes -o wide 의 INTERNAL-IP (또는 콘솔 > 인스턴스 <클러스터>-node-*)
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for ip in var.k8s_node_ips : can(cidrhost("${ip}/32", 0))])
    error_message = "k8s_node_ips에는 IPv4 주소만 넣는다 (/32는 붙이지 않는다)."
  }
}

variable "k8s_ingress_nodeport" {
  description = "ingress-nginx NodePort (infra/k8s/values/ingress-nginx.yaml와 같은 값). lb가 나가도록 Egress에 연다"
  type        = number
  default     = 30080
}
