# Terraform state

*[Leia em português](README.md)*

Terraform state holds addresses, ARNs and sometimes sensitive values of everything in the account.
This module creates where it lives and runs once, with local state, before the rest of the
infrastructure.

| Control | How |
|---|---|
| Encrypted with our own key | SSE-KMS with `aws_kms_key.estado`, automatic yearly rotation |
| History | versioning on; old versions expire after 90 days |
| Never public | all four public access block settings on |
| TLS only | bucket policy denies any request without `aws:SecureTransport` |
| No concurrent apply | DynamoDB lock table, encrypted with the same key and with point-in-time recovery |
| Not deleted by mistake | `prevent_destroy` on the bucket |

After applying it, the rest of the infrastructure uses the backend described in
`backend.hcl.exemplo`.

## How it is tested

`testar_no_emulador.py` really applies the module on moto, an open-source AWS emulator, and checks
every row of the table above through the API. It runs in CI, in the "Infraestrutura" job, with no
credentials and no cost. Switching the bucket encryption to AES256 makes the own-key check fail.

moto plays the role the plan gave LocalStack: it is open source, needs no account and covers S3,
KMS, DynamoDB and STS, which is all this module uses. RDS, MSK and ECR are not emulated; the rest of
the infrastructure stays covered by `terraform validate` and Checkov.
