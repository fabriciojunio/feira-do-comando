"""Métricas do programa de AppSec, para quem decide.

Lê o registro de achados (docs/seguranca/achados.csv) e os aceites com prazo dos
osv-scanner.toml, e escreve um resumo em Markdown: tempo até corrigir por
severidade, quanto ficou dentro do prazo, o que está aberto e há quanto tempo,
falsos positivos e exceções vencidas ou perto de vencer.

Uso:
  python scripts/metricas_do_programa.py docs/seguranca/achados.csv \\
      --aceites feira-do-comando=osv-scanner.toml --saida docs/seguranca/metricas.md
"""

import argparse
import csv
import statistics
import tomllib
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path

# Prazo para corrigir, em dias, a partir da descoberta.
PRAZO = {"crítica": 7, "alta": 30, "média": 90, "baixa": 180}
ORDEM = list(PRAZO)
AVISO_DE_VENCIMENTO = 30

SEVERIDADES = {
    "critical": "crítica", "crítica": "crítica",
    "high": "alta", "alta": "alta",
    "medium": "média", "média": "média",
    "low": "baixa", "baixa": "baixa", "warning": "baixa", "note": "baixa",
}
DESFECHOS = {"corrigido", "falso_positivo", "aberto"}


@dataclass(frozen=True)
class Achado:
    repositorio: str
    severidade: str
    aberto_em: datetime
    fechado_em: datetime | None
    desfecho: str

    def dias(self, agora: datetime) -> float:
        fim = self.fechado_em or agora
        return (fim - self.aberto_em).total_seconds() / 86400


def data_hora(texto: str) -> datetime:
    valor = datetime.fromisoformat(texto.replace("Z", "+00:00"))
    return valor if valor.tzinfo else valor.replace(tzinfo=timezone.utc)


def ler_achados(caminho: Path) -> list[Achado]:
    achados = []
    with caminho.open(encoding="utf-8", newline="") as arquivo:
        for numero, linha in enumerate(csv.DictReader(arquivo), start=2):
            severidade = SEVERIDADES.get(linha["severidade"].strip().lower())
            desfecho = linha["desfecho"].strip()
            if severidade is None or desfecho not in DESFECHOS:
                raise ValueError(f"linha {numero}: severidade ou desfecho inválido")
            fechado = linha["fechado_em"].strip()
            if (desfecho == "aberto") != (not fechado):
                raise ValueError(f"linha {numero}: aberto não tem data de fechamento, e fechado precisa ter")
            achados.append(Achado(linha["repositorio"].strip(), severidade, data_hora(linha["aberto_em"]),
                                  data_hora(fechado) if fechado else None, desfecho))
    return achados


def ler_aceites(origens: list[tuple[str, Path]]) -> list[tuple[str, str, date | None, str]]:
    aceites = []
    for repositorio, caminho in origens:
        for aceite in tomllib.loads(caminho.read_text(encoding="utf-8")).get("IgnoredVulns", []):
            prazo = aceite.get("ignoreUntil")
            prazo = prazo.date() if isinstance(prazo, datetime) else prazo
            aceites.append((repositorio, aceite["id"], prazo, aceite.get("reason", "")))
    return aceites


def situacao_do_aceite(prazo: date | None, hoje: date) -> str:
    if prazo is None:
        return "sem prazo"
    if prazo < hoje:
        return "vencido"
    if (prazo - hoje).days <= AVISO_DE_VENCIMENTO:
        return "vence em até 30 dias"
    return "vigente"


def formatar(dias: float) -> str:
    return f"{dias:.1f}".replace(".", ",")


def relatorio(achados: list[Achado], aceites: list, agora: datetime) -> str:
    corrigidos = [a for a in achados if a.desfecho == "corrigido"]
    abertos = [a for a in achados if a.desfecho == "aberto"]
    falsos = [a for a in achados if a.desfecho == "falso_positivo"]
    hoje = agora.date()

    linhas = [
        "# Métricas do programa de AppSec",
        "",
        f"Gerado em {agora:%d/%m/%Y} a partir de `docs/seguranca/achados.csv` e dos aceites com prazo "
        "do OSV-Scanner. Prazo para corrigir: crítica 7 dias, alta 30, média 90, baixa 180.",
        "",
        f"**{len(achados)} achados registrados**: {len(corrigidos)} corrigidos, {len(abertos)} abertos "
        f"e {len(falsos)} falsos positivos.",
        "",
        "## Tempo até corrigir",
        "",
        "| Severidade | Corrigidos | Mediana (dias) | Média (dias) | Dentro do prazo |",
        "|---|---|---|---|---|",
    ]
    for severidade in ORDEM:
        grupo = [a.dias(agora) for a in corrigidos if a.severidade == severidade]
        if not grupo:
            linhas.append(f"| {severidade} | 0 | - | - | - |")
            continue
        no_prazo = sum(1 for d in grupo if d <= PRAZO[severidade])
        linhas.append(f"| {severidade} | {len(grupo)} | {formatar(statistics.median(grupo))} | "
                      f"{formatar(statistics.mean(grupo))} | {round(100 * no_prazo / len(grupo))}% |")

    linhas += ["", "## Abertos", ""]
    if abertos:
        linhas += ["| Severidade | Abertos | Mais antigo (dias) | Com prazo estourado |", "|---|---|---|---|"]
        for severidade in ORDEM:
            grupo = [a.dias(agora) for a in abertos if a.severidade == severidade]
            if grupo:
                estourados = sum(1 for d in grupo if d > PRAZO[severidade])
                linhas.append(f"| {severidade} | {len(grupo)} | {formatar(max(grupo))} | {estourados} |")
    else:
        linhas.append("Nenhum achado aberto.")

    triados = len(achados)
    taxa = round(100 * len(falsos) / triados) if triados else 0
    linhas += ["", "## Falsos positivos", "",
               f"{len(falsos)} de {triados} achados ({taxa}%) eram falsos positivos, cada um fechado com a "
               "justificativa registrada na ferramenta de origem."]

    linhas += ["", "## Exceções aceitas com prazo", ""]
    if aceites:
        linhas += ["| Repositório | Vulnerabilidade | Prazo | Situação |", "|---|---|---|---|"]
        for repositorio, identificador, prazo, _ in sorted(aceites, key=lambda a: (a[2] or date.max, a[1])):
            texto_prazo = f"{prazo:%d/%m/%Y}" if prazo else "-"
            linhas.append(f"| {repositorio} | {identificador} | {texto_prazo} | {situacao_do_aceite(prazo, hoje)} |")
        vencidos = sum(1 for a in aceites if situacao_do_aceite(a[2], hoje) == "vencido")
        linhas += ["", f"{vencidos} exceção(ões) vencida(s). Exceção vencida volta a reprovar a CI."]
    else:
        linhas.append("Nenhuma exceção aceita.")
    return "\n".join(linhas) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("achados", type=Path)
    parser.add_argument("--aceites", action="append", default=[], metavar="REPOSITORIO=ARQUIVO")
    parser.add_argument("--saida", type=Path)
    args = parser.parse_args()

    origens = []
    for item in args.aceites:
        repositorio, _, caminho = item.partition("=")
        origens.append((repositorio, Path(caminho)))
    texto = relatorio(ler_achados(args.achados), ler_aceites(origens), datetime.now(timezone.utc))
    if args.saida:
        args.saida.write_text(texto, encoding="utf-8")
    else:
        print(texto, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
