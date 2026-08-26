# Versoes travadas.
#
# Sem trava, um "terraform init" daqui a seis meses baixa um provedor novo e o
# plano muda sozinho. Em infraestrutura isso significa descobrir a diferenca
# durante um apply, que e o pior momento possivel.
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.regiao

  default_tags {
    tags = {
      projeto    = "feira-do-comando"
      ambiente   = var.ambiente
      gerenciado = "terraform"
    }
  }
}
