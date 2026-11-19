# 격리망 입구: 공인 IP · 방화벽(Ingress) · static NAT(lb) · 포트포워딩(db)
# 공인 IP는 격리망이 VM 배치로 구현된 뒤에 할당된다 → 루트에서 depends_on = [module.lb, module.db]
# "공인 IP"는 실제로는 교내망(10.0.0.0/16) 주소다(W1). VPN과 Shared Network에서 닿는다.

# ---- lb: static NAT ----
# 들어오는 443·80·22와 lb가 나가는 트래픽이 모두 이 IP를 쓴다
# → 앱 계층(web VM·K8s 노드) 방화벽에서 lb 출발지를 이 IP 하나로 좁힐 수 있다
resource "cloudstack_ipaddress" "lb" {
  network_id = var.network_id
  zone       = var.zone
}

resource "cloudstack_static_nat" "lb" {
  ip_address_id      = cloudstack_ipaddress.lb.id
  virtual_machine_id = var.lb_id
}

resource "cloudstack_firewall" "lb" {
  ip_address_id = cloudstack_ipaddress.lb.id

  rule {
    cidr_list = ["0.0.0.0/0"] # 서비스 입구
    protocol  = "tcp"
    ports     = ["80", "443"]
  }

  rule {
    cidr_list = var.admin_cidrs # 관리 SSH는 VPN 출발지만
    protocol  = "tcp"
    ports     = ["22"]
  }
}

# ---- db: 포트포워딩 ----
# DB는 외부에 직접 노출하지 않고 필요한 포트만 넘긴다: 관리 SSH, 앱 계층 → 5432
resource "cloudstack_ipaddress" "db" {
  network_id = var.network_id
  zone       = var.zone
}

resource "cloudstack_port_forward" "db" {
  ip_address_id = cloudstack_ipaddress.db.id

  forward {
    protocol           = "tcp"
    private_port       = 22
    public_port        = var.db_ssh_port
    virtual_machine_id = var.db_id
  }

  forward {
    protocol           = "tcp"
    private_port       = 5432
    public_port        = 5432
    virtual_machine_id = var.db_id
  }
}

resource "cloudstack_firewall" "db" {
  ip_address_id = cloudstack_ipaddress.db.id

  rule {
    cidr_list = var.admin_cidrs
    protocol  = "tcp"
    ports     = [tostring(var.db_ssh_port)]
  }

  rule {
    cidr_list = var.db_client_cidrs # 앱 계층만 (VM 단계: web VM IP / K8s 단계: 노드 IP)
    protocol  = "tcp"
    ports     = ["5432"]
  }
}

# ---- Egress(아웃바운드) ----
# VR이 뜬 뒤에만 만들 수 있어(네트워크만 있을 땐 530 Failed to create firewall rule) 여기(VM 배치 이후)에 둔다.
# 그 전에 부팅한 VM의 cloud-init apt 업데이트는 실패할 수 있지만, 패키지 설치는 Ansible이 Egress가 열린 뒤에 한다.
# 이 수업의 격리망 오퍼링은 egressdefaultpolicy=false — 아웃바운드 기본 전면 차단(W1).
# 필요한 것만 연다: DNS, HTTP·HTTPS(apt), lb → 앱 계층 포트.
# provider 0.5의 egress 규칙은 출발지(cidr_list)만 지정할 수 있어 목적지는 앱 계층 방화벽(ufw·NetworkPolicy)이 좁힌다.
resource "cloudstack_egress_firewall" "this" {
  network_id = var.network_id

  rule {
    cidr_list = [var.network_cidr]
    protocol  = "udp"
    ports     = ["53"]
  }

  rule {
    cidr_list = [var.network_cidr]
    protocol  = "tcp"
    ports     = concat(["53", "80", "443"], var.app_ports)
  }
}
