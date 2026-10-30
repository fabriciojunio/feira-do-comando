"""Gera a configuração do Grype a partir dos aceites do osv-scanner.toml.

Duas ferramentas, uma fonte: a falha aceita no OSV, com motivo e prazo, é a mesma que o Grype deve
ignorar. Aceite vencido fica de fora, e o Grype volta a reprovar junto com o OSV.

Uso: python scripts/aceites_para_grype.py osv-scanner.toml .grype-ci.yaml
"""

import sys
import tomllib
from datetime import date, datetime
from pathlib import Path


def vigentes(aceites: list[dict], hoje: date) -> list[dict]:
    resultado = []
    for aceite in aceites:
        prazo = aceite.get("ignoreUntil")
        prazo = prazo.date() if isinstance(prazo, datetime) else prazo
        if prazo is None or prazo >= hoje:
            resultado.append(aceite)
    return resultado


def configuracao(aceites: list[dict]) -> str:
    linhas = ["# Gerado por scripts/aceites_para_grype.py a partir do osv-scanner.toml. Não editar.", "ignore:"]
    if not aceites:
        return "# Gerado por scripts/aceites_para_grype.py: nenhum aceite vigente.\nignore: []\n"
    for aceite in aceites:
        motivo = aceite.get("reason", "").replace("\n", " ").replace('"', "'")
        linhas.append(f'  - vulnerability: "{aceite["id"]}"  # {motivo}')
    return "\n".join(linhas) + "\n"


def main() -> int:
    origem, destino = Path(sys.argv[1]), Path(sys.argv[2])
    aceites = tomllib.loads(origem.read_text(encoding="utf-8")).get("IgnoredVulns", [])
    validos = vigentes(aceites, date.today())
    destino.write_text(configuracao(validos), encoding="utf-8")
    print(f"{len(validos)} de {len(aceites)} aceites vigentes em {destino}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
