output "endereco_dos_bancos" {
  description = "Endereco de cada PostgreSQL. Entra na variavel DATABASE_URL de cada servico."
  value       = { for nome, banco in aws_db_instance.servico : nome => banco.address }
}

output "kafka_bootstrap" {
  description = "Endereco do Kafka, para a variavel KAFKA_BOOTSTRAP."
  value       = aws_msk_serverless_cluster.principal.bootstrap_brokers_sasl_iam
}

output "repositorios" {
  description = "URL de cada repositorio de imagem."
  value       = { for nome, repo in aws_ecr_repository.servico : nome => repo.repository_url }
}

output "id_da_vpc" {
  description = "Id da VPC, util para anexar recursos criados fora deste modulo."
  value       = aws_vpc.principal.id
}
