variable "names" {
  description = "VM 이름 목록 (대수 = 길이)"
  type        = list(string)
}

variable "role" {
  description = "역할 (lb / web / db)"
  type        = string
}

variable "offering_id" {
  description = "컴퓨트 오퍼링 ID"
  type        = string
}

variable "common" {
  description = "zone, template_id, network_id, keypair, root_disk, ssh_key"
  type = object({
    zone        = string
    template_id = string
    network_id  = string
    keypair     = string
    root_disk   = number
    ssh_key     = string
  })
}

variable "data_disks" {
  description = "VM마다 붙일 추가 디스크 {용도 = {offering, device_id}}. 기본 없음"
  type = map(object({
    offering  = string
    device_id = number
  }))
  default = {}
}
