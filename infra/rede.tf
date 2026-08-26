# Rede.
#
# Sub-redes publicas so para o balanceador; banco e servico ficam em sub-rede
# privada. E a diferenca entre um banco alcancavel da internet e um que so
# responde de dentro da VPC, e nao existe motivo para o primeiro.

data "aws_availability_zones" "disponiveis" {
  state = "available"
}

locals {
  # Duas zonas, que e o minimo que o RDS exige para o grupo de sub-redes.
  zonas = slice(data.aws_availability_zones.disponiveis.names, 0, 2)
  nome  = "feira-${var.ambiente}"
}

resource "aws_vpc" "principal" {
  cidr_block           = var.cidr_da_rede
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.nome }
}

resource "aws_internet_gateway" "saida" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = "${local.nome}-igw" }
}

resource "aws_subnet" "publica" {
  count = length(local.zonas)

  vpc_id                  = aws_vpc.principal.id
  cidr_block              = cidrsubnet(var.cidr_da_rede, 8, count.index)
  availability_zone       = local.zonas[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.nome}-publica-${count.index}" }
}

resource "aws_subnet" "privada" {
  count = length(local.zonas)

  vpc_id            = aws_vpc.principal.id
  cidr_block        = cidrsubnet(var.cidr_da_rede, 8, count.index + 10)
  availability_zone = local.zonas[count.index]

  tags = { Name = "${local.nome}-privada-${count.index}" }
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.principal.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.saida.id
  }

  tags = { Name = "${local.nome}-rota-publica" }
}

resource "aws_route_table_association" "publica" {
  count = length(aws_subnet.publica)

  subnet_id      = aws_subnet.publica[count.index].id
  route_table_id = aws_route_table.publica.id
}

# ---- grupos de seguranca ----

resource "aws_security_group" "servicos" {
  name        = "${local.nome}-servicos"
  description = "Servicos da aplicacao"
  vpc_id      = aws_vpc.principal.id

  egress {
    description = "Saida liberada: os servicos precisam falar com o Kafka e com o RDS"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.nome}-servicos" }
}

resource "aws_security_group" "banco" {
  name        = "${local.nome}-banco"
  description = "PostgreSQL, alcancavel apenas pelos servicos"
  vpc_id      = aws_vpc.principal.id

  # Sem regra de entrada por CIDR de proposito. Quem entra e o grupo dos
  # servicos, e nao uma faixa de IP: assim, subir um servico novo dentro do
  # grupo ja resolve o acesso, e nada fora dele alcanca o banco.
  ingress {
    description     = "PostgreSQL vindo dos servicos"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.servicos.id]
  }

  tags = { Name = "${local.nome}-banco" }
}
