# Painel do programa de AppSec

*[Read in English](programa.en.md)*

O [`metricas.md`](metricas.md) mostra como o programa está indo, para quem decide e não lê
alerta de ferramenta: tempo até corrigir por severidade, quanto disso ficou dentro do prazo, o que
está aberto, quantos achados eram falsos positivos e quais exceções estão perto de vencer.

## De onde vêm os números

- [`achados.csv`](achados.csv): um achado por linha, de todos os repositórios do programa, com
  data de abertura, data de fechamento, desfecho e a evidência. Entram alertas do CodeQL e do
  Dependency-Check e também o que as provas automatizadas e a revisão de código acharam.
- os `osv-scanner.toml` de cada repositório: as falhas aceitas, cada uma com motivo e prazo.

Nada é estimado. As datas saem da API do GitHub (abertura e fechamento do alerta) ou do Git
(primeiro commit da correção e merge em produção).

## Prazos

| Severidade | Prazo para corrigir |
|---|---|
| crítica | 7 dias |
| alta | 30 dias |
| média | 90 dias |
| baixa | 180 dias |

Severidade de ferramenta que não usa essa escala (`note`, `warning` do CodeQL) conta como baixa.

## Como atualizar

```bash
python scripts/metricas_do_programa.py docs/seguranca/achados.csv \
  --aceites feira-do-comando=osv-scanner.toml \
  --aceites authcore=<caminho>/osv-scanner.toml \
  --saida docs/seguranca/metricas.md
```

A CI roda os testes do script e escreve o resumo com os aceites deste repositório no resumo da
execução. O `metricas.md` completo, com os aceites de todos os repositórios, é gerado na máquina,
porque a CI de um repositório não lê os arquivos dos outros.

## O que os números de 30/10/2026 dizem

Os 40 achados críticos levaram 17,4 dias para ser corrigidos, e nenhum ficou dentro dos 7 dias.
Todos vieram do mesmo lugar: o codereview-ai preso no Spring Boot 3.3, sem correção desde junho.
Enquanto o framework não fosse trocado, nenhuma dessas falhas tinha conserto pontual. A lição é de
processo: versão de framework sem suporte precisa entrar na fila como achado crítico no dia em que
o suporte acaba, e não quando o scanner começa a apontar CVE.

As exceções aceitas (12, nenhuma vencida) têm o mesmo padrão em dois repositórios: duas falhas do
Spring Framework que só se corrigem com o Spring Boot 4, migração já feita no codereview-ai.
