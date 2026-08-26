# Registro das imagens.
#
# Um repositorio por servico, com varredura de vulnerabilidade ligada. A
# politica de retencao existe porque ECR cobra por armazenamento, e imagem de
# build antigo se acumula rapido num pipeline que publica a cada push.

resource "aws_ecr_repository" "servico" {
  for_each = toset(concat(var.servicos, ["consulta"]))

  name                 = "feira/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = "feira-${each.key}" }
}

resource "aws_ecr_lifecycle_policy" "limpeza" {
  for_each = aws_ecr_repository.servico

  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Guarda as 10 imagens mais recentes"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
