# Rede.
#
# Sub-redes publicas so para o balanceador; banco e servico ficam em sub-rede
# privada. E a diferenca entre um banco alcancavel da internet e um que so
# responde de dentro da VPC, e nao existe motivo para o primeiro.

data "aws_availability_zones" "disponiveis" {
  #checkov:skip=CKV_AWS_394: o slice(..., 0, 2) abaixo ja fixa duas zonas; zona nova da AWS nao muda o resultado
  state = "available"
}

locals {
  # Duas zonas, que e o minimo que o RDS exige para o grupo de sub-redes.
  zonas = slice(data.aws_availability_zones.disponiveis.names, 0, 2)
  nome  = "feira-${var.ambiente}"
}

resource "aws_vpc" "principal" {
  #checkov:skip=CKV2_AWS_11: flow log no CloudWatch cobra por GB; ambiente de portfolio sem custo. Ligar antes de producao
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

  vpc_id            = aws_vpc.principal.id
  cidr_block        = cidrsubnet(var.cidr_da_rede, 8, count.index)
  availability_zone = local.zonas[count.index]
  # Nada sobe direto nesta sub-rede: so o balanceador, que recebe o IP dele
  # pela AWS. IP publico automatico so serviria para expor por engano algo
  # lancado aqui.
  map_public_ip_on_launch = false

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

# O grupo padrao da VPC vem aberto entre os membros. Sem regra nenhuma, um
# recurso criado sem grupo explicito fica isolado em vez de herdar acesso.
resource "aws_default_security_group" "fechado" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = "${local.nome}-padrao-fechado" }
}

resource "aws_security_group" "servicos" {
  #checkov:skip=CKV2_AWS_5: os servicos rodam no cluster dos manifestos em k8s/, que usa este grupo fora deste modulo
  name        = "${local.nome}-servicos"
  description = "Servicos da aplicacao"
  vpc_id      = aws_vpc.principal.id

  # Saida restrita a dois destinos: dentro da VPC, para Kafka e RDS, e HTTPS
  # para as APIs da AWS (ECR, IAM do Kafka). Antes era tudo para qualquer
  # lugar, o que deixava um servico comprometido mandar dado para fora por
  # qualquer porta.
  egress {
    description = "Kafka e RDS dentro da VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.cidr_da_rede]
  }

  egress {
    description = "HTTPS para as APIs da AWS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
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
