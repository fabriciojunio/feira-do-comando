# Kafka gerenciado.
#
# O MSK Serverless cobra por uso em vez de por hora de broker, o que casa com
# um ambiente que fica a maior parte do tempo parado. Em producao com volume
# constante, um cluster provisionado sai mais barato, e o comentario fica aqui
# para a escolha ser revista quando a carga mudar.

resource "aws_security_group" "kafka" {
  name        = "${local.nome}-kafka"
  description = "Kafka, alcancavel apenas pelos servicos"
  vpc_id      = aws_vpc.principal.id

  ingress {
    description     = "Kafka vindo dos servicos"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [aws_security_group.servicos.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.nome}-kafka" }
}

resource "aws_msk_serverless_cluster" "principal" {
  cluster_name = "${local.nome}-kafka"

  vpc_config {
    subnet_ids         = aws_subnet.privada[*].id
    security_group_ids = [aws_security_group.kafka.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }

  tags = { Name = "${local.nome}-kafka" }
}
