resource "cloudstack_instance" "this" {
  count = length(var.names)

  name             = var.names[count.index]
  display_name     = var.names[count.index]
  service_offering = var.offering_id
  template         = var.common.template_id
  network_id       = var.common.network_id
  zone             = var.common.zone
  keypair          = var.common.keypair
  root_disk_size   = var.common.root_disk
  expunge          = true # destroy 시 즉시 삭제해 쿼터를 바로 돌려받는다

  user_data = templatefile("${path.root}/templates/cloud-init.tftpl", {
    role    = var.role
    ssh_key = var.common.ssh_key
  })
}

locals {
  # (VM 인덱스, 디스크 용도) 조합을 펼친다. 예: "db01-xxx-data"
  disks = {
    for pair in setproduct(range(length(var.names)), keys(var.data_disks)) :
    "${var.names[pair[0]]}-${pair[1]}" => {
      vm_index  = pair[0]
      offering  = var.data_disks[pair[1]].offering
      device_id = var.data_disks[pair[1]].device_id
    }
  }
}

resource "cloudstack_disk" "this" {
  for_each = local.disks

  name               = each.key
  disk_offering      = each.value.offering
  zone               = var.common.zone
  attach             = true
  device_id          = each.value.device_id
  virtual_machine_id = cloudstack_instance.this[each.value.vm_index].id
}
