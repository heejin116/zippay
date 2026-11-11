terraform {
  required_version = ">= 1.0"
  required_providers {
    cloudstack = {
      source  = "cloudstack/cloudstack"
      version = "~> 0.5.0" # 수업(W9)에서 검증한 버전으로 고정
    }
  }
}

# 자격증명은 코드에 없다. terraform.tfvars(커밋 금지) 또는 TF_VAR_api_key 등 환경변수로 받는다.
provider "cloudstack" {
  api_url    = var.api_url
  api_key    = var.api_key
  secret_key = var.secret_key
}
