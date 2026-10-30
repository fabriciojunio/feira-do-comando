# Módulo que cria onde o estado do Terraform da Feira vai morar. Roda uma vez,
# com estado local, antes de tudo; depois o resto da infraestrutura aponta o
# backend para o que ele criou (ver backend.hcl.exemplo).
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }
}

provider "aws" {
  region = var.regiao

  default_tags {
    tags = {
      projeto    = "feira-do-comando"
      gerenciado = "terraform"
      finalidade = "estado-do-terraform"
    }
  }
}

variable "regiao" {
  description = "Região da AWS."
  type        = string
  default     = "us-east-1"
}

variable "prefixo" {
  description = "Prefixo dos nomes. O bucket ganha o id da conta para ser único."
  type        = string
  default     = "feira-tfstate"
}
