"""Exporta as métricas do programa de AppSec no formato do Prometheus.

Lê a mesma fonte do resumo em Markdown (docs/seguranca/achados.csv e os aceites
com prazo), para o número do painel e o do documento nunca divergirem. Reaproveita
a leitura e os prazos de metricas_do_programa.py.

As métricas seguem o que o mercado acompanha num painel de AppSec: backlog por
severidade, MTTR (tempo médio até corrigir), cumprimento de SLA, abertos com prazo
estourado e exceções de risco por situação.

Uso:
  python scripts/metricas_prometheus.py docs/seguranca/achados.csv \\
      --aceites feira-do-comando=osv-scanner.toml --saida metricas.prom
  python scripts/metricas_prometheus.py docs/seguranca/achados.csv --servir 9114
"""

import argparse
import statistics
from datetime import datetime, timezone
from pathlib import Path

from metricas_do_programa import ORDEM, PRAZO, ler_aceites, ler_achados, situacao_do_aceite


# Rótulo de métrica é para máquina: vai em ASCII, sem acento nem espaço, para o
# PromQL e o painel não precisarem casar acento. O texto humano com acento fica
# no resumo em Markdown.
ASCII = {"crítica": "critica", "média": "media"}
SITUACAO = {
    "vigente": "vigente",
    "vence em até 30 dias": "vence_em_30_dias",
    "vencido": "vencido",
    "sem prazo": "sem_prazo",
}


def _linha(nome: str, rotulos: dict[str, str], valor: float) -> str:
    etiquetas = ",".join(f'{chave}="{texto}"' for chave, texto in rotulos.items())
    return f"{nome}{{{etiquetas}}} {valor:g}"


def _sev(severidade: str) -> str:
    return ASCII.get(severidade, severidade)


def exposicao(achados, aceites, agora: datetime) -> str:
    corrigidos = [a for a in achados if a.desfecho == "corrigido"]
    abertos = [a for a in achados if a.desfecho == "aberto"]
    falsos = [a for a in achados if a.desfecho == "falso_positivo"]
    hoje = agora.date()

    saida: list[str] = []

    saida += [
        "# HELP appsec_achados Achados por severidade e desfecho",
        "# TYPE appsec_achados gauge",
    ]
    for severidade in ORDEM:
        for desfecho in ("corrigido", "aberto", "falso_positivo"):
            n = sum(1 for a in achados if a.severidade == severidade and a.desfecho == desfecho)
            saida.append(_linha("appsec_achados", {"severidade": _sev(severidade), "desfecho": desfecho}, n))

    saida += [
        "# HELP appsec_mttr_dias Tempo médio até corrigir, por severidade, em dias",
        "# TYPE appsec_mttr_dias gauge",
    ]
    for severidade in ORDEM:
        grupo = [a.dias(agora) for a in corrigidos if a.severidade == severidade]
        if grupo:
            saida.append(_linha("appsec_mttr_dias", {"severidade": _sev(severidade)}, statistics.mean(grupo)))

    saida += [
        "# HELP appsec_mttr_mediana_dias Mediana do tempo até corrigir, por severidade, em dias",
        "# TYPE appsec_mttr_mediana_dias gauge",
    ]
    for severidade in ORDEM:
        grupo = [a.dias(agora) for a in corrigidos if a.severidade == severidade]
        if grupo:
            saida.append(_linha("appsec_mttr_mediana_dias", {"severidade": _sev(severidade)}, statistics.median(grupo)))

    saida += [
        "# HELP appsec_sla_cumprido_proporcao Proporção dos corrigidos dentro do prazo (0 a 1), por severidade",
        "# TYPE appsec_sla_cumprido_proporcao gauge",
    ]
    for severidade in ORDEM:
        grupo = [a.dias(agora) for a in corrigidos if a.severidade == severidade]
        if grupo:
            no_prazo = sum(1 for d in grupo if d <= PRAZO[severidade])
            saida.append(_linha("appsec_sla_cumprido_proporcao", {"severidade": _sev(severidade)}, no_prazo / len(grupo)))

    saida += [
        "# HELP appsec_abertos_estourados Achados abertos com o prazo de correção estourado, por severidade",
        "# TYPE appsec_abertos_estourados gauge",
    ]
    for severidade in ORDEM:
        estourados = sum(1 for a in abertos if a.severidade == severidade and a.dias(agora) > PRAZO[severidade])
        saida.append(_linha("appsec_abertos_estourados", {"severidade": _sev(severidade)}, estourados))

    saida += [
        "# HELP appsec_excecoes Exceções de risco aceitas, por situação",
        "# TYPE appsec_excecoes gauge",
    ]
    situacoes: dict[str, int] = {}
    for _, _, prazo, _ in aceites:
        situacao = situacao_do_aceite(prazo, hoje)
        situacoes[situacao] = situacoes.get(situacao, 0) + 1
    for situacao in ("vigente", "vence em até 30 dias", "vencido", "sem prazo"):
        saida.append(_linha("appsec_excecoes", {"situacao": SITUACAO[situacao]}, situacoes.get(situacao, 0)))

    saida += [
        "# HELP appsec_achados_total Total de achados registrados",
        "# TYPE appsec_achados_total gauge",
        f"appsec_achados_total {len(achados)}",
        "# HELP appsec_falsos_positivos_total Total de falsos positivos",
        "# TYPE appsec_falsos_positivos_total gauge",
        f"appsec_falsos_positivos_total {len(falsos)}",
    ]
    return "\n".join(saida) + "\n"


def gerar(args) -> str:
    origens = []
    for item in args.aceites:
        repositorio, _, caminho = item.partition("=")
        origens.append((repositorio, Path(caminho)))
    return exposicao(ler_achados(args.achados), ler_aceites(origens), datetime.now(timezone.utc))


def servir(porta: int, args) -> None:
    from http.server import BaseHTTPRequestHandler, HTTPServer

    class Manipulador(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (nome exigido pela biblioteca)
            if self.path not in ("/", "/metrics"):
                self.send_error(404)
                return
            corpo = gerar(args).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(corpo)))
            self.end_headers()
            self.wfile.write(corpo)

        def log_message(self, *_args) -> None:
            pass

    HTTPServer(("0.0.0.0", porta), Manipulador).serve_forever()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("achados", type=Path)
    parser.add_argument("--aceites", action="append", default=[], metavar="REPOSITORIO=ARQUIVO")
    parser.add_argument("--saida", type=Path)
    parser.add_argument("--servir", type=int, metavar="PORTA")
    args = parser.parse_args()

    if args.servir:
        servir(args.servir, args)
        return 0
    texto = gerar(args)
    if args.saida:
        args.saida.write_text(texto, encoding="utf-8")
    else:
        print(texto, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
