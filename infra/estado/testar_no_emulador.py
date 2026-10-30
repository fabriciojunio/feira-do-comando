"""Aplica o módulo de estado num emulador da AWS (moto) e confere cada controle.

terraform validate só diz que a sintaxe está certa. Aqui o módulo é aplicado de
verdade, sem custo, e cada promessa do código é conferida pela API: bucket
versionado, cifrado com a chave própria, sem acesso público, só aceitando TLS,
chave com rotação e tabela de trava cifrada.

Uso: python infra/estado/testar_no_emulador.py   (com o terraform no PATH)
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import boto3

PORTA = 5055
ENDPOINT = f"http://127.0.0.1:{PORTA}"
MODULO = Path(__file__).parent

SOBRESCRITA = f"""
provider "aws" {{
  access_key                  = "teste"
  secret_key                  = "teste"
  skip_credentials_validation = true
  skip_requesting_account_id  = false
  skip_metadata_api_check     = true
  s3_use_path_style           = true
  endpoints {{
    s3       = "{ENDPOINT}"
    kms      = "{ENDPOINT}"
    dynamodb = "{ENDPOINT}"
    sts      = "{ENDPOINT}"
    iam      = "{ENDPOINT}"
  }}
}}
"""


def esperar_emulador():
    for _ in range(60):
        try:
            socket.create_connection(("127.0.0.1", PORTA), timeout=1).close()
            return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError("o emulador não subiu")


def main() -> int:
    terraform = shutil.which("terraform")
    if terraform is None:
        raise RuntimeError("terraform não está no PATH")
    os.environ.update(AWS_ACCESS_KEY_ID="teste", AWS_SECRET_ACCESS_KEY="teste", AWS_DEFAULT_REGION="us-east-1")
    emulador = subprocess.Popen([sys.executable, "-m", "moto.server", "-p", str(PORTA)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    trabalho = Path(tempfile.mkdtemp())
    try:
        esperar_emulador()
        for arquivo in MODULO.glob("*.tf"):
            shutil.copy(arquivo, trabalho)
        (trabalho / "provider_override.tf").write_text(SOBRESCRITA, encoding="utf-8")
        for comando in (["init", "-input=false"], ["apply", "-auto-approve", "-input=false"]):
            subprocess.run([terraform, *comando], cwd=trabalho, check=True, stdout=subprocess.DEVNULL)
        saidas = json.loads(subprocess.run([terraform, "output", "-json"], cwd=trabalho, check=True,
                                           capture_output=True, text=True).stdout)

        cliente = {s: boto3.client(s, endpoint_url=ENDPOINT, region_name="us-east-1") for s in ("s3", "kms", "dynamodb")}
        bucket, chave, tabela = saidas["bucket"]["value"], saidas["chave"]["value"], saidas["tabela_da_trava"]["value"]
        s3, kms, ddb = cliente["s3"], cliente["kms"], cliente["dynamodb"]

        cifra = s3.get_bucket_encryption(Bucket=bucket)["ServerSideEncryptionConfiguration"]["Rules"][0]
        padrao = cifra["ApplyServerSideEncryptionByDefault"]
        bloqueio = s3.get_public_access_block(Bucket=bucket)["PublicAccessBlockConfiguration"]
        politica = json.loads(s3.get_bucket_policy(Bucket=bucket)["Policy"])["Statement"][0]
        sse_tabela = ddb.describe_table(TableName=tabela)["Table"]["SSEDescription"]

        conferencias = {
            "bucket versionado": s3.get_bucket_versioning(Bucket=bucket).get("Status") == "Enabled",
            "bucket cifrado com a chave própria": padrao["SSEAlgorithm"] == "aws:kms" and padrao["KMSMasterKeyID"] == chave,
            "acesso público bloqueado nas quatro chaves": all(bloqueio.values()) and len(bloqueio) == 4,
            "política recusa requisição sem TLS": politica["Effect"] == "Deny"
                and politica["Condition"]["Bool"]["aws:SecureTransport"] == "false",
            "chave com rotação automática": kms.get_key_rotation_status(KeyId=chave)["KeyRotationEnabled"],
            "tabela de trava com a chave LockID": ddb.describe_table(TableName=tabela)["Table"]["KeySchema"][0]["AttributeName"] == "LockID",
            "tabela de trava cifrada com KMS": sse_tabela["Status"] == "ENABLED" and sse_tabela["SSEType"] == "KMS",
        }
        for nome, ok in conferencias.items():
            print(("ok    " if ok else "FALHOU") + "  " + nome)
        return 0 if all(conferencias.values()) else 1
    finally:
        emulador.terminate()
        shutil.rmtree(trabalho, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
