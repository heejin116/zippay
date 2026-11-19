# 격리 네트워크.
# 격리망의 가상 라우터(VR)가 Source NAT·방화벽·static NAT·포트포워딩을 맡는다(경계 1, W7 구조).
resource "cloudstack_network" "this" {
  name             = var.name
  display_text     = var.name
  cidr             = var.cidr
  network_offering = var.network_offering # 콘솔 표시 문구가 아니라 offering name (probe에서 확인)
  zone             = var.zone
}

# Egress 규칙은 modules/edge에 있다: VR이 뜬 뒤(첫 VM 배치 후)에만 만들 수 있다(11/18 apply에서 530 오류로 확인).
