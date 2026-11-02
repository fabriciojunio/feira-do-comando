# Painel de métricas do programa de AppSec

Painel no padrão de mercado: Prometheus coleta e o Grafana mostra, com o
dashboard versionado como código. A fonte é o mesmo
[`docs/seguranca/achados.csv`](../docs/seguranca/achados.csv) do resumo em
Markdown, para o número do painel e o do documento nunca divergirem.

## O que mostra

As métricas que um programa de AppSec acompanha: tempo médio até corrigir (MTTR)
por severidade, cumprimento de SLA, backlog aberto por severidade, abertos com o
prazo estourado e exceções de risco por situação.

## Como rodar

```
docker compose -f observabilidade/docker-compose.yml up
```

Grafana em http://localhost:3000 (usuário `admin`, a senha do
`GF_SECURITY_ADMIN_PASSWORD` do compose). O painel "Programa de AppSec" e a fonte
Prometheus já sobem provisionados.

## Peças

| Arquivo | O que é |
|---|---|
| [`../scripts/metricas_prometheus.py`](../scripts/metricas_prometheus.py) | exportador: lê o `achados.csv` e serve as métricas em `/metrics` |
| [`prometheus/prometheus.yml`](prometheus/prometheus.yml) | coleta do exportador a cada 30 s |
| [`grafana/dashboards/appsec-programa.json`](grafana/dashboards/appsec-programa.json) | o painel como código |
| [`grafana/provisioning/`](grafana/provisioning/) | fonte de dados e carga do painel |

A CI testa o exportador, gera as métricas a partir do registro (reprova se ele
estiver inconsistente) e confere que o JSON do painel é válido.
