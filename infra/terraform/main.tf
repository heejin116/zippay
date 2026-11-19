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

module "edge" {
  source = "./modules/edge"

  network_id      = module.network.id
  network_cidr    = module.network.cidr
  app_ports       = var.app_ports
  zone            = data.cloudstack_zone.dku.name
  lb_id           = module.lb.ids[0]
  db_id           = module.db.ids[0]
  admin_cidrs     = var.admin_cidrs
  db_client_cidrs = [for ip in module.web.ips : "${ip}/32"]
  db_ssh_port     = var.db_ssh_port

  # 공인 IP는 격리망이 구현(첫 VM 배치)된 뒤에 할당된다 (probe에서 확인)
  depends_on = [module.lb, module.db]
}
