# Chave própria para os dados em repouso.
#
# Com a chave padrão da AWS ninguém consegue tirar o acesso de um serviço sem
# mexer no IAM inteiro, e a trilha do CloudTrail não separa o uso desta
# aplicação do resto da conta. Com chave própria a política dela diz quem usa,
# a rotação anual é automática e desligar a chave corta o acesso aos dados de
# uma vez. Custa US$ 1 por mês por chave mais as chamadas.

data "aws_caller_identity" "atual" {}

resource "aws_kms_key" "dados" {
  description             = "Dados em repouso da Feira do Comando (bancos e senha mestre)"
  enable_key_rotation     = true
  rotation_period_in_days = 365
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Sem esta declaração a chave fica sem dono e só o suporte da AWS a
        # recupera. O acesso de verdade continua controlado pelo IAM da conta.
        Sid       = "AdministracaoPelaConta"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.atual.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # Uso só por intermédio do RDS e do Secrets Manager desta região:
        # ninguém decifra dado do banco chamando o KMS direto.
        Sid       = "UsoPeloRdsESecretsManager"
        Effect    = "Allow"
        Principal = { AWS = "*" }
        Action    = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:CreateGrant", "kms:DescribeKey"]
        Resource  = "*"
        Condition = {
          StringEquals = {
            "kms:CallerAccount" = data.aws_caller_identity.atual.account_id
            "kms:ViaService" = [
              "rds.${var.regiao}.amazonaws.com",
              "secretsmanager.${var.regiao}.amazonaws.com",
            ]
          }
        }
      },
    ]
  })

  tags = { Name = "${local.nome}-dados" }
}

resource "aws_kms_alias" "dados" {
  name          = "alias/${local.nome}-dados"
  target_key_id = aws_kms_key.dados.key_id
}
