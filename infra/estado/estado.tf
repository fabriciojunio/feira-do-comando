# O estado do Terraform guarda endereço, ARN e às vezes valor sensível de tudo o
# que existe na conta. Ele mora num bucket que só aceita TLS, nunca fica
# público, guarda cada versão anterior e é cifrado com chave própria. A trava
# no DynamoDB impede dois applies ao mesmo tempo sobre o mesmo estado.

data "aws_caller_identity" "atual" {}

locals {
  bucket = "${var.prefixo}-${data.aws_caller_identity.atual.account_id}"
}

resource "aws_kms_key" "estado" {
  description             = "Estado do Terraform da Feira do Comando"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AdministracaoPelaConta"
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.atual.account_id}:root" }
      Action    = "kms:*"
      Resource  = "*"
    }]
  })
}

resource "aws_kms_alias" "estado" {
  name          = "alias/${var.prefixo}"
  target_key_id = aws_kms_key.estado.key_id
}

resource "aws_s3_bucket" "estado" {
  #checkov:skip=CKV_AWS_144: replicação entre regiões dobra o custo; o versionamento já cobre apagamento acidental
  #checkov:skip=CKV_AWS_18: log de acesso exigiria um segundo bucket só para isso; o CloudTrail registra as chamadas
  #checkov:skip=CKV2_AWS_62: notificação de evento não tem destino neste projeto
  bucket = local.bucket

  # Apagar o bucket do estado por engano apagaria a memória do que existe na conta.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "estado" {
  bucket = aws_s3_bucket.estado.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "estado" {
  bucket = aws_s3_bucket.estado.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.estado.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "estado" {
  bucket                  = aws_s3_bucket.estado.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "estado" {
  bucket = aws_s3_bucket.estado.id
  rule {
    id     = "versoes-antigas"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_policy" "somente_tls" {
  bucket = aws_s3_bucket.estado.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "SomenteTls"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.estado.arn, "${aws_s3_bucket.estado.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_dynamodb_table" "trava" {
  name         = "${var.prefixo}-trava"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.estado.arn
  }

  point_in_time_recovery {
    enabled = true
  }
}

output "bucket" {
  value = aws_s3_bucket.estado.id
}

output "tabela_da_trava" {
  value = aws_dynamodb_table.trava.name
}

output "chave" {
  value = aws_kms_key.estado.arn
}
