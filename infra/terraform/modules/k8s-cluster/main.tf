# K8s 단계 앱 계층: Solid Cloud CKS 클러스터 (W11 코드 기반)
# - Shared Network에만 만든다. 격리망은 노드와 망의 MTU 불일치로 Starting에서 멈춘다(W11).
# - CKS가 노드 VM 생성, 런타임 설치, kubeadm 초기화, Calico 설치를 대신한다.
# - 노드는 Medium(2 vCPU / 4GiB) 고정. control은 트래픽을 받지 않고 클러스터 관리만 한다.
# - 주의: Starting에서 10분 넘게 멈추면 삭제가 거부된다. apply 전에 Dashboard에서 남은 자원을 확인한다.
resource "cloudstack_kubernetes_cluster" "this" {
  name               = var.name
  description        = var.description
  zone               = var.zone
  kubernetes_version = var.kubernetes_version
  service_offering   = var.service_offering
  control_nodes_size = var.control_count
  size               = var.worker_count
  network_id         = var.network_id
  noderootdisksize   = var.node_root_disk_gb
  keypair            = var.keypair # 기존 zippay-<학번> 키페어 (같은 공개키는 다른 이름으로 중복 등록 불가)
}
