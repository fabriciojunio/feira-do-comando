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

# Mesmo aceite do CKV_AWS_129 abaixo, para o Semgrep: exportar log para o CloudWatch cobra por GB.
# nosemgrep: terraform.aws.security.aws-db-instance-no-logging.aws-db-instance-no-logging
resource "aws_db_instance" "servico" {
  #checkov:skip=CKV_AWS_157: Multi-AZ dobra o custo; portfolio sem custo. Obrigatorio em producao
  #checkov:skip=CKV_AWS_118: monitoramento avancado cobra pelo CloudWatch; portfolio sem custo
  #checkov:skip=CKV_AWS_353: Performance Insights nao existe na classe micro usada aqui
  #checkov:skip=CKV_AWS_129: exportar log para o CloudWatch cobra por GB; portfolio sem custo
  #checkov:skip=CKV2_AWS_30: log de toda consulta gravaria dado pessoal de cliente e pedido no log, contra a LGPD
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

  # Autenticacao por IAM ligada: o servico pode entrar com token de vida curta
  # em vez da senha mestre. Nao custa nada.
  iam_database_authentication_enabled = true

  # Snapshot sem as tags perde a marca de ambiente e de dono, e e por elas que
  # a limpeza e o controle de custo encontram o que apagar.
  copy_tags_to_snapshot = true

  tags = { Name = "${local.nome}-${each.key}" }
}
