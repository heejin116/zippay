# Phase 1: LB VM 1 + web VM 2 + DB VM 1 (7 vCPU / 14GiB, 현재 한도 8 / 16 안)
# Shared Network에 보안 그룹이 없어 계층 간 접근 제어는 Ansible이 VM 안 ufw로 건다.

resource "cloudstack_ssh_keypair" "admin" {
  name       = "zippay-${var.student_id}"
  public_key = var.ssh_public_key
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
}

module "lb" {
  source = "./modules/vm"

  names       = ["lb-${var.student_id}"]
  role        = "lb"
  offering_id = data.cloudstack_service_offering.small.id
  common      = local.common
}

module "web" {
  source = "./modules/vm"

  names       = [for i in range(var.web_count) : format("web%02d-%s", i + 1, var.student_id)]
  role        = "web"
  offering_id = data.cloudstack_service_offering.medium.id
  common      = local.common
}

module "db" {
  source = "./modules/vm"

  names       = ["db01-${var.student_id}"]
  role        = "db"
  offering_id = data.cloudstack_service_offering.medium.id
  common      = local.common

  # 데이터는 루트가 아닌 별도 디스크(VM을 다시 만들어도 디스크는 분리·재연결 가능), 백업도 별도 디스크
  # device_id로 장치 순서를 고정해 Ansible이 구분한다: 1 → /dev/vdb(data), 2 → /dev/vdc(backup)
  data_disks = {
    data   = { offering = var.db_disk_offering, device_id = 1 }
    backup = { offering = var.db_disk_offering, device_id = 2 }
  }
}
