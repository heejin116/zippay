# 자식 모듈도 provider 출처를 선언해야 한다. 없으면 Terraform이 hashicorp/cloudstack(존재하지 않음)으로 추정한다.
terraform {
  required_providers {
    cloudstack = {
      source = "cloudstack/cloudstack"
    }
  }
}
