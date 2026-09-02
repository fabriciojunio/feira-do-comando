# Feira do Comando

*[Leia em português](README.md)*

Event-driven order platform in Java 21 and Spring Boot 3.5: three services, a
Kafka topic between them, and a saga that has to survive messages arriving
twice, out of order, and too late.

The interesting part is not the happy path. It is what the code does when the
broker misbehaves, and that is what the tests are about.

## What it does

A customer places an order. Stock reserves the units. Payment charges the card.
If the card is declined, the reservation goes back. If the card is approved
while the order is already being cancelled, the money is refunded.

```
                 ┌──────────────┐
   POST /pedidos │   pedidos    │  saga orchestrator, outbox, order state
                 └──────┬───────┘
                        │ feira.pedidos
             ┌──────────┴──────────┐
             ▼                     ▼
      ┌─────────────┐       ┌───────────────┐
      │   estoque   │       │  pagamentos   │
      └──────┬──────┘       └───────┬───────┘
             │ feira.estoque        │ feira.pagamentos
             └──────────┬───────────┘
                        ▼
                    pedidos
```

Each service owns its own PostgreSQL database. They never share a table, which
is the whole point: if they did, the outbox would be pointless because you
could just write to your neighbour's table inside the same transaction.

## The three problems this project is really about

**1. The dual write.** Saving to the database and publishing to Kafka are two
systems and there is no transaction covering both. Publish first and the
process may die before saving; save first and it may die before publishing.
Solved with a [transactional outbox](docs/adr/002-outbox-em-vez-de-publicar-direto.md):
the message is written in the same transaction as the state change, and a
separate publisher sends it. That trades "may lose an event" for "may send the
same event twice", which has a known answer.

**2. At-least-once delivery.** Kafka redelivers. Every consumer keeps an inbox
table whose primary key is the event id, so the second attempt hits the
constraint instead of applying the effect again. The check before it is only an
optimisation; the database is what guarantees.

**3. There is no distributed rollback.** A saga is a sequence of local
transactions, each with a compensating action. The compensations here are
release-the-reservation and refund-the-payment, and both must be idempotent,
because the message that triggers them will arrive more than once.

## Running it

```bash
git clone <repo> feira && cd feira
docker compose up -d
```

Then:

```bash
# stock a product
curl -X POST localhost:8082/api/v1/estoque/itens \
  -H 'Content-Type: application/json' \
  -d '{"sku":"CAF-500","descricao":"Cafe torrado 500g","quantidade":10}'

# place an order
curl -X POST localhost:8081/api/v1/pedidos \
  -H 'Content-Type: application/json' \
  -H 'X-Cliente-Id: cliente-1' \
  -H 'Idempotency-Key: minha-chave-1' \
  -d '{"itens":[{"sku":"CAF-500","descricao":"Cafe","quantidade":2,"precoUnitario":"24.90"}]}'

# watch the saga move
curl localhost:8081/api/v1/pedidos/<id> -H 'X-Cliente-Id: cliente-1'
```

| Where | What |
|---|---|
| `localhost:8081` | orders API, `/swagger-ui.html` |
| `localhost:8082` | stock API |
| `localhost:8083` | payments API |
| `localhost:8090` | Redpanda console, to watch the topics |

**Forcing a failure.** The simulated acquirer declines by cents value, the same
way a real acquirer's sandbox does: an order totalling `x.13` fails for
insufficient funds, `x.66` for suspected fraud, `x.99` with a temporary error.
That is how you see the compensation path without waiting for luck.

## Tests

```bash
mvn verify
```

189 tests, none of them requiring Docker. PostgreSQL is started as a real
process by the test itself and Kafka comes from `spring-kafka-test`, because a
test that only runs when someone remembers to start the infrastructure is a
test that does not run.

| Suite | What it proves |
|---|---|
| `SagaDoPedidoTest` (36) | the state machine under repeated, out-of-order and late events, including the race between payment approval and cancellation |
| `ConcorrenciaDeReservaTest` (6) | ten threads fighting over three units never oversell, with real threads and a real database |
| `FluxoDePedidoTest` (21) | the whole application: outbox reaches the topic, consumer reads it, saga advances |
| `ArquiteturaTest` (12) | the layering still holds, checked by ArchUnit rather than by a diagram |
| `ContratosTest` (14) | events survive the round trip through JSON |
| `VazaoDePedidosTest` (3) | throughput under concurrent load, and that a repeated idempotency key under a race answers every caller correctly |
| `web/` (42) | the saga narrative the customer reads, and that the forced-decline demo is reachable at all |
| `ProjecaoDoPedidoTest` (15) | the read model against a real MongoDB: repeated delivery, out-of-order events, and the refund showing up |
| `FluxoDaProjecaoTest` (6) | topic to HTTP response, with real Kafka and real MongoDB |

Three of those suites earned their keep during the build. ArchUnit caught a
package cycle between `aplicacao` and `infraestrutura`. The end-to-end test
caught an `ObjectMapper` bean that was silently disabling RFC 9457 error
serialisation across the whole API. And the load test caught the one described
below.

### Throughput

The load test prints its measurement into the build output:

```
[vazao] 120 orders created in 1.11s across 8 threads: 108.1 orders/s (9.2 ms per order)
```

Read that number for what it is. It runs against an embedded PostgreSQL, in the
same JVM, through MockMvc, so it measures neither the network nor the proxy. It
is not a substitute for k6 against a production-shaped environment. What it is
good for is a floor and a trend: a regression that doubles the cost per order
shows up in the build rather than in the demo.

It also caught a real defect. Twelve concurrent requests carrying the same
idempotency key did produce exactly one order, which is the guarantee that
matters, but the eleven losers of the race received a 500. The recovery path
re-read the winning order from inside the very transaction the constraint
violation had already doomed, so the read was useless and the commit failed
anyway. The transactional boundary now ends before the recovery, in
`GravadorDePedido`, and every caller gets the winning order back.

## Observability

Metrics answer "it is slow". Traces answer "it is slow *where*", which on an order crossing four
services is the first question in any incident.

The work was not enabling tracing, which is one dependency and three lines. It was the outbox:
the message is written inside the transaction that changes the order and published later, on
another thread, once that request is gone. Trace context lives on the thread, so it died at
commit and the dashboard showed disconnected traces instead of one whole saga.

The W3C `traceparent` is now stored in an outbox column and propagated in a record header.
Consumption resumes it in a container-factory interceptor rather than in each `@KafkaListener`,
so the fifth consumer someone writes is traced by default. Every database query becomes a span
too, which is where an N+1 becomes visible.

It ships disabled: an exporter with no collector fills the log with connection errors. Turn it on
with `RASTRO_ATIVO=true` and a collector address. See
[006](docs/adr/006-rastro-distribuido.md).

## Load testing

The throughput test in the build measures the cost of the code: in-process, through MockMvc,
against an embedded PostgreSQL.

`carga/pedidos.js` measures the cost of the system, with k6 against `docker compose`: real
network, HTTP serialisation, the edge gateway and the connection pool under pressure.

```bash
docker compose up -d --wait
k6 run carga/pedidos.js
```

It runs in CI on every push to the main branch, not on every pull request, because it brings the
whole system up and would cost minutes that fast feedback cannot pay.

It is still not a test against a production-shaped environment: one machine, empty databases. It
is a floor and a trend, not a capacity number.

## Design decisions

Each of these has a short document explaining the trade-off, including what was
given up:

- [001 — saga inside the aggregate, not in a separate orchestrator](docs/adr/001-saga-no-agregado.md)
- [002 — outbox instead of publishing directly](docs/adr/002-outbox-em-vez-de-publicar-direto.md)
- [003 — optimistic locking on stock](docs/adr/003-trava-otimista-no-estoque.md)
- [004 — one database per service](docs/adr/004-um-banco-por-servico.md)
- [005 — embedded PostgreSQL and Kafka in tests](docs/adr/005-infra-embutida-nos-testes.md)
- [006 — distributed tracing that survives the outbox](docs/adr/006-rastro-distribuido.md)

## The read model

Each service owns its database, which is the point. The cost is that no single
one of them can answer "how is my order doing": the saga state lives in orders,
the reservation in inventory, and the charge in payments. Until now the browser
did the join, with three calls.

`servico-consulta` consumes the same Kafka events under its own consumer group
and keeps one denormalized document per order in **MongoDB**. One read returns
the order, its items, the charge and the timeline.

MongoDB rather than a fourth PostgreSQL because what the screen wants *is* a
document. In tables that becomes a four-way join to build a response that is
never read in pieces. And the choice is cheap to defend from the other side:
this data is **not** the source of truth. It is derived from the events and can
be thrown away and rebuilt from the topic. Strong consistency stays in each
service's PostgreSQL, where it is actually enforced.

What it does not give you is immediate consistency. The projection follows the
topic, so there is a window between the order changing state and the read
reflecting it. For following a saga that is fine, and the screen already polls.
For deciding whether an order can still be cancelled, the order service remains
the authority.

Idempotency lives in the document itself, in `eventosAplicados`, rather than in
the platform's inbox. The projection already has to record what it applied so
it does not duplicate the timeline, and a second control table would be the
same fact in two places.

## Infrastructure as code

`infra/` holds Terraform for AWS: a VPC with private subnets, one RDS
PostgreSQL per service, MSK Serverless for Kafka, and one ECR repository per
image.

`terraform fmt` and `terraform validate` run on every build. Neither needs AWS
credentials: they check syntax, references between resources and attribute
types. What they do **not** check is whether the resources actually exist in an
account, and nothing here has been applied to one.

## Kubernetes

Plain manifests under [`k8s/`](k8s/), applied with `kubectl apply -k k8s/`.
Three databases, one per service, for the same reason the compose file has
three. Two replicas per service, which the outbox allows because `SKIP LOCKED`
lets instances share the message table without publishing anything twice.

Two levels of checking run on every build. `kubeconform` validates the
manifests against the Kubernetes API schemas, so a wrong `apiVersion` or a
misspelled field fails the pipeline rather than the `apply`. Then a real `kind`
cluster is created and `kubectl apply -k k8s/` runs against it, waiting for the
broker and the three databases to roll out. That second step is what catches a
bad probe, an impossible resource request or a mismatched selector, which
schema validation cannot see.

They have still never run on a production cluster, and the directory README
says so.

## Stack

Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL, MongoDB, Flyway, Kafka, Redpanda,
Micrometer with Prometheus and OpenTelemetry, OpenAPI, JUnit 5, AssertJ, Awaitility, ArchUnit,
k6, Docker, Kubernetes, Terraform.

Errors follow [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)
(Problem Details), with a stable `codigo` extension so clients can branch
without parsing prose.

## What is not here

Said upfront rather than discovered later:

- **No real acquirer.** `AdquirenteSimulado` sits behind an interface with the
  behaviour of a real one, including declines. Swapping it for Cielo or Stone
  means writing one class.
- **No authentication.** The client id arrives in a header. In a real system it
  comes from a validated token; keeping it simple leaves the focus on the saga.
- **Never applied to a real cluster or a real AWS account.** The Kubernetes
  manifests are validated against the API schemas and applied to a throwaway
  `kind` cluster on every build, and the Terraform is checked with `validate`,
  but neither has run against production infrastructure.
