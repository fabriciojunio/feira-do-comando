# Um PostgreSQL por servico.
#
# Pelo mesmo motivo do docker-compose e dos manifestos: banco compartilhado
# deixaria um servico escrever na tabela do vizinho dentro da mesma transacao,
# e o outbox perderia o proposito.

resource "aws_db_subnet_group" "privado" {
  name       = "${local.nome}-privado"
  subnet_ids = aws_subnet.privada[*].id

  tags = { Name = "${local.nome}-privado" }
}

resource "aws_db_instance" "servico" {
  for_each = toset(var.servicos)

  identifier     = "${local.nome}-${each.key}"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = var.classe_do_banco

  allocated_storage     = 20
  max_allocated_storage = 50
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "feira_${each.key}"
  username = "feira"
  password = var.senha_do_banco

  db_subnet_group_name   = aws_db_subnet_group.privado.name
  vpc_security_group_ids = [aws_security_group.banco.id]
  publicly_accessible    = false

  backup_retention_period = var.ambiente == "prod" ? 7 : 1
  skip_final_snapshot     = var.ambiente != "prod"
  deletion_protection     = var.ambiente == "prod"

  # Em dev, aplicar mudanca na hora evita esperar a janela de manutencao para
  # ver o efeito. Em producao isso derrubaria o banco no meio do dia.
  apply_immediately = var.ambiente != "prod"

  auto_minor_version_upgrade = true

  tags = { Name = "${local.nome}-${each.key}" }
}
