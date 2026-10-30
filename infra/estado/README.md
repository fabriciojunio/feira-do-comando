# Estado do Terraform

*[Read this in English](README.en.md)*

O estado do Terraform guarda endereço, ARN e às vezes valor sensível de tudo que existe na conta.
Este módulo cria onde ele mora e roda uma vez, com estado local, antes do resto da infraestrutura.

| Controle | Como |
|---|---|
| Cifrado com chave própria | SSE-KMS com `aws_kms_key.estado`, rotação anual automática |
| Histórico | versionamento ligado; versões antigas expiram em 90 dias |
| Nunca público | as quatro chaves do bloqueio de acesso público ligadas |
| Só TLS | política do bucket recusa qualquer requisição sem `aws:SecureTransport` |
| Sem apply concorrente | tabela de trava no DynamoDB, cifrada com a mesma chave e com recuperação no tempo |
| Não some por engano | `prevent_destroy` no bucket |

Depois de aplicar, o resto da infraestrutura usa o backend descrito em `backend.hcl.exemplo`.

## Como é testado

`testar_no_emulador.py` aplica o módulo de verdade no moto, emulador aberto da AWS, e confere cada
linha da tabela acima pela API. Roda na CI, no job "Infraestrutura", sem credencial e sem custo.
Trocar a cifra do bucket para AES256 faz a conferência da chave própria reprovar.

O moto faz o papel que o plano previa para o LocalStack: é aberto, roda sem conta e cobre S3, KMS,
DynamoDB e STS, que é tudo o que este módulo usa. RDS, MSK e ECR não entram no emulador; o resto
da infraestrutura continua coberto por `terraform validate` e pelo Checkov.
