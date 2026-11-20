# VM 단계: 격리망(lb·DB) + Shared Network(web VM 2) — 노션 3장·DESIGN.md
#   경계 1: 격리망 가상 라우터(VR) 방화벽·static NAT·포트포워딩·Egress (modules/network, modules/edge)
#   경계 2: 각 VM ufw (Ansible)
# 앱 계층은 Shared Network: K8s 단계의 CKS가 격리망에 만들어지지 않아(W11, MTU) 비교 조건을 맞추려고 VM 단계도 Shared에 둔다.

resource "cloudstack_ssh_keypair" "admin" {
  name       = "zippay-${var.student_id}"
  public_key = var.ssh_public_key
}

module "network" {
  source = "./modules/network"

  name             = "zippay-net-${var.student_id}"
  cidr             = var.isolated_cidr
  zone             = data.cloudstack_zone.dku.name
  network_offering = var.isolated_network_offering
}

locals {
  common = {
    zone        = data.cloudstack_zone.dku.name
    template_id = data.cloudstack_template.ubuntu.id
    network_id  = var.shared_network_id
    keypair     = cloudstack_ssh_keypair.admin.name
    root_disk   = var.root_disk_size
    ssh_key     = var.ssh_public_key
  }
  # lb·DB는 격리망
  isolated = merge(local.common, { network_id = module.network.id })
}

module "lb" {
  source = "./modules/vm"

  names       = ["lb-${var.student_id}"]
  role        = "lb"
  offering_id = data.cloudstack_service_offering.small.id
  common      = local.isolated
}

module "web" {
  source = "./modules/vm"

  names       = [for i in range(var.web_count) : format("web%02d-%s", i + 1, var.student_id)]
  role        = "web"
  offering_id = data.cloudstack_service_offering.medium.id
  common      = local.common # Shared Network
}

module "db" {
  source = "./modules/vm"

  names       = ["db01-${var.student_id}"]
  role        = "db"
  offering_id = data.cloudstack_service_offering.small.id # 자원 한도 안에서 K8s 단계와 같은 DB로 비교하려고 Small
  common      = local.isolated

  # 데이터는 루트가 아닌 별도 디스크(VM을 다시 만들어도 디스크는 분리·재연결 가능), 백업도 별도 디스크
  # device_id로 장치 순서를 고정해 Ansible이 구분한다: 1 → /dev/vdb(data), 2 → /dev/vdc(backup)
  data_disks = {
    data   = { offering = var.db_disk_offering, device_id = 1 }
    backup = { offering = var.db_disk_offering, device_id = 2 }
  }
}

# K8s 단계 앱 계층. web VM을 남겨 둔 채 만들어 같은 lb·DB로 비교한다.
# 키페어는 VM과 같은 zippay-<학번>을 참조한다(같은 공개키는 다른 이름으로 중복 등록 불가).
module "k8s" {
  source = "./modules/k8s-cluster"
  count  = var.k8s_enabled ? 1 : 0

  name               = "k8s-${var.student_id}"
  zone               = data.cloudstack_zone.dku.name
  kubernetes_version = var.k8s_version
  service_offering   = data.cloudstack_service_offering.medium.name
  control_count      = 1
  worker_count       = var.k8s_worker_count
  network_id         = var.shared_network_id
  node_root_disk_gb  = var.k8s_node_root_disk_gb
  keypair            = cloudstack_ssh_keypair.admin.name
}

module "edge" {
  source = "./modules/edge"

  network_id       = module.network.id
  network_cidr     = module.network.cidr
  app_ports        = var.app_ports
  monitoring_ports = var.monitoring_ports
  zone             = data.cloudstack_zone.dku.name
  lb_id            = module.lb.ids[0]
  db_id            = module.db.ids[0]
  admin_cidrs      = var.admin_cidrs
  db_client_cidrs  = [for ip in concat(module.web.ips, var.k8s_node_ips) : "${ip}/32"] # VM 단계 web + K8s 단계 worker
  db_ssh_port      = var.db_ssh_port

  # 공인 IP는 격리망이 구현(첫 VM 배치)된 뒤에 할당된다 (probe에서 확인)
  depends_on = [module.lb, module.db]
}
