# ADR 005: PostgreSQL e Kafka embutidos nos testes

**Data:** 2026-08-26
**Status:** Aceito

## Contexto

Os testes que importam neste projeto são justamente os que precisam de
infraestrutura: o outbox só prova alguma coisa se a mensagem chegar mesmo a um
tópico, e a trava otimista só prova alguma coisa contra um banco de verdade.

O caminho padrão é Testcontainers. Ele exige Docker na máquina e no runner, e a
máquina de desenvolvimento deste projeto não tem Docker.

O risco de aceitar essa limitação é conhecido, e já tinha acontecido em outro
repositório: o teste de integração vira um job separado, o job separado vira
opcional, e o teste para de rodar. Quando volta a ser executado, meses depois,
está quebrado.

## Decisão

- **PostgreSQL**: `io.zonky.test:embedded-postgres`, que baixa e sobe um
  processo real do Postgres, sem Docker.
- **Kafka**: `@EmbeddedKafka`, do `spring-kafka-test`.

Os dois rodam no ciclo normal de `mvn test`. Não existe profile separado nem
`-DskipITs`.

## Consequências positivas

- **O SQL e o esquema são executados a cada build**, na mesma versão de banco
  que roda em produção. Índice parcial, `SKIP LOCKED` e `@Version` só funcionam
  de verdade no Postgres.
- **A saga é exercitada de ponta a ponta**: outbox grava, publicador envia,
  consumidor lê, estado muda. Nenhum teste de unidade cobre isso.
- **Roda em qualquer lugar**, incluindo um runner sem Docker.
- **Já pagou o próprio custo.** Foi o teste de ponta a ponta que revelou que
  faltava a flag `-parameters` no compilador e que um bean de `ObjectMapper`
  próprio estava desligando a serialização do Problem Details na API inteira.
  Nenhum teste de unidade veria nem um nem outro.

## Consequências negativas

- **A primeira execução baixa o binário do Postgres**, algumas dezenas de
  megabytes. As seguintes usam o cache do Maven.
- **O build fica mais lento.** A suíte inteira leva por volta de dois minutos,
  contra segundos se fosse só unidade.
- **É preciso declarar o binário de cada sistema operacional** que vai rodar o
  build. Hoje são Windows e Linux; um Mac com ARM exigiria mais um.
- **O Kafka embutido não é o Kafka de produção.** Ele cobre protocolo, partição
  e consumo, mas não rebalanceamento sob carga nem falha de broker. Para isso
  não há substituto para um ambiente de verdade.
- **O `truncate` entre testes acopla o teste à lista de tabelas** do esquema.

## Alternativas consideradas

**Testcontainers.** A escolha padrão e a certa quando há Docker. Chegou a entrar
no `pom.xml` e saiu por causa do ambiente. Migrar de volta é trocar duas
anotações.

**H2 em modo de compatibilidade.** Rápido e sem download, e não entende índice
parcial, `SKIP LOCKED` nem `jsonb`. Testaria outra coisa e daria uma sensação
falsa de segurança.

**Mock do `KafkaTemplate` e do repositório.** É o que a maioria dos projetos faz
e é exatamente o que não prova nada sobre entrega ao menos uma vez, ordem de
partição ou conflito de versão.
