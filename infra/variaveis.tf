variable "regiao" {
  description = "Regiao da AWS. sa-east-1 e Sao Paulo, e a latencia importa para quem acessa do Brasil."
  type        = string
  default     = "sa-east-1"
}

variable "ambiente" {
  description = "Nome do ambiente, usado no nome dos recursos e nas etiquetas."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "hml", "prod"], var.ambiente)
    error_message = "O ambiente precisa ser dev, hml ou prod."
  }
}

variable "cidr_da_rede" {
  description = "Faixa de enderecos da VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "classe_do_banco" {
  description = <<-TEXTO
    Classe das instancias RDS.

    db.t4g.micro cabe na camada gratuita nos primeiros doze meses. Trocar isto
    sem olhar a fatura e a forma mais comum de um projeto de estudo virar
    cobranca inesperada.
  TEXTO
  type        = string
  default     = "db.t4g.micro"
}

variable "senha_do_banco" {
  description = "Senha mestre do PostgreSQL. Vem de variavel de ambiente, nunca do arquivo."
  type        = string
  sensitive   = true
}

variable "servicos" {
  description = "Os servicos que ganham banco proprio. Um banco por servico e o ponto do desenho."
  type        = list(string)
  default     = ["pedidos", "estoque", "pagamentos"]
}
