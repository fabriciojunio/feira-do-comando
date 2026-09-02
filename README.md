# Feira do Comando

*[Read this in English](README.en.md)*

Plataforma de pedidos orientada a eventos em Java 21 e Spring Boot 3.5: quatro serviços, tópicos
do Kafka entre eles, e uma saga que precisa sobreviver a mensagem chegando duas vezes, fora de
ordem e atrasada.

A parte interessante não é o caminho feliz. É o que o código faz quando o corretor se comporta
mal, e é sobre isso que os testes são.

## O que ele faz

Um cliente faz um pedido. O estoque reserva as unidades. Pagamentos cobra o cartão. Se o cartão é
recusado, a reserva volta. Se o cartão é aprovado enquanto o pedido já está sendo cancelado, o
dinheiro é estornado.

```
                 ┌──────────────┐
   POST /pedidos │   pedidos    │  orquestra a saga, outbox, estado do pedido
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

Cada serviço é dono do próprio PostgreSQL. Eles nunca compartilham tabela, e essa é a questão
inteira: se compartilhassem, o outbox não teria razão de existir, porque bastaria escrever na
tabela do vizinho dentro da mesma transação.

## Os três problemas que este projeto é realmente sobre

**1. A escrita dupla.** Salvar no banco e publicar no Kafka são dois sistemas, e não existe
transação cobrindo os dois. Publicar primeiro e o processo pode morrer antes de salvar; salvar
primeiro e ele pode morrer antes de publicar. Resolvido com
[outbox transacional](docs/adr/002-outbox-em-vez-de-publicar-direto.md): a mensagem é gravada na
mesma transação da mudança de estado, e um publicador separado a envia. Troca "pode perder
evento" por "pode enviar o mesmo evento duas vezes", que tem resposta conhecida.

**2. Entrega pelo menos uma vez.** O Kafka reentrega. Todo consumidor mantém uma tabela de inbox
cuja chave primária é o id do evento, então a segunda tentativa bate na restrição em vez de
aplicar o efeito de novo. A conferência antes disso é só otimização; quem garante é o banco.

**3. Não existe rollback distribuído.** Uma saga é uma sequência de transações locais, cada uma
com uma ação compensatória. As compensações aqui são devolver a reserva e estornar o pagamento, e
as duas precisam ser idempotentes, porque a mensagem que as dispara vai chegar mais de uma vez.

## Rodando

```bash
git clone https://github.com/fabriciojunio/feira-do-comando.git feira && cd feira
docker compose up -d
```

```bash
# cadastra um produto no estoque
curl -X POST http://localhost:8081/api/v1/produtos \
  -H "Content-Type: application/json" \
  -d '{"sku":"CAFE-500","descricao":"Café 500g","quantidade":10}'

# faz um pedido
curl -X POST http://localhost:8080/api/v1/pedidos \
  -H "Content-Type: application/json" \
  -H "X-Cliente-Id: 11111111-1111-1111-1111-111111111111" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"itens":[{"sku":"CAFE-500","quantidade":2,"precoUnitario":29.90}]}'

# acompanha a saga andando
curl http://localhost:8083/api/v1/consulta/pedidos/<id>
```

## Testes

```bash
mvn verify
```

**189 testes, e nenhum precisa de Docker instalado.** O PostgreSQL sobe como processo de verdade
dentro do próprio teste e o Kafka vem do `spring-kafka-test`, porque teste que só roda quando
alguém lembra de subir a infraestrutura é teste que não roda.

| Suíte | O que prova |
|---|---|
| `SagaDoPedidoTest` (36) | a máquina de estados sob evento repetido, fora de ordem e atrasado, incluindo a corrida entre aprovação do pagamento e cancelamento |
| `ConcorrenciaDeReservaTest` (6) | dez threads disputando três unidades nunca vendem a mais, com threads de verdade e banco de verdade |
| `FluxoDePedidoTest` (21) | a aplicação inteira: o outbox chega ao tópico, o consumidor lê, a saga anda |
| `ArquiteturaTest` (12) | as camadas continuam de pé, conferidas por ArchUnit e não por um diagrama |
| `ContratosTest` (14) | os eventos sobrevivem à ida e volta pelo JSON |
| `VazaoDePedidosTest` (3) | vazão sob carga concorrente, e que chave de idempotência repetida numa corrida responde certo para todo mundo |
| `ProjecaoDoPedidoTest` (15) | o modelo de leitura contra um MongoDB real: entrega repetida, evento fora de ordem e o estorno aparecendo |
| `FluxoDaProjecaoTest` (6) | do tópico até a resposta HTTP, com Kafka e MongoDB reais |
| `web/` (42) | a narrativa da saga que o cliente lê, e que a demonstração de recusa forçada é alcançável |

Três dessas suítes se pagaram durante a construção. O ArchUnit pegou um ciclo de pacote entre
`aplicacao` e `infraestrutura`. O teste de ponta a ponta pegou um bean de `ObjectMapper` que
estava desligando em silêncio a serialização de erro no formato RFC 9457 em toda a API. E o teste
de carga pegou o defeito descrito abaixo.

### Vazão

O teste de carga imprime a medição na saída do build:

```
[vazao] 120 pedidos criados em 1,11s com 8 threads: 108,1 pedidos/s (9,2 ms por pedido)
```

Leia esse número pelo que ele é. Roda contra PostgreSQL embarcado, na mesma JVM, por MockMvc,
então não mede rede nem proxy. Não substitui k6 contra um ambiente com forma de produção. Para o
que ele serve é como piso e como tendência: uma regressão que dobre o custo por pedido aparece no
build em vez de aparecer na demonstração.

Ele também pegou um defeito real. Doze requisições concorrentes com a mesma chave de idempotência
produziram exatamente um pedido, que é a garantia que importa, mas os onze perdedores da corrida
receberam 500. O caminho de recuperação relia o pedido vencedor de dentro da mesma transação que
a violação de restrição já tinha condenado, então a leitura não valia nada e o commit falhava do
mesmo jeito. A fronteira transacional agora termina antes da recuperação, em `GravadorDePedido`,
e todo mundo recebe o pedido vencedor de volta.

## Observabilidade

Métrica responde "está lento". Rastro responde "está lento onde", que num pedido atravessando
quatro serviços é a primeira pergunta de qualquer plantão.

O trabalho não foi ligar o rastreamento, que é uma dependência e três linhas. Foi o outbox: a
mensagem é gravada na transação que muda o pedido e publicada depois, por outra thread, quando
aquela requisição já acabou. Contexto de rastro vive na thread, então morria no commit e o painel
mostrava rastros desligados em vez de uma saga inteira.

O `traceparent` do W3C passou a ser gravado numa coluna do outbox e propagado em cabeçalho do
registro. O consumo é retomado num interceptador da fábrica, e não em cada `@KafkaListener`, então
o quinto consumidor que alguém escrever já nasce rastreado. Cada consulta ao banco também vira um
trecho, que é onde o problema de uma consulta virar N fica visível.

Vem desligado: exportador sem coletor enche o log de erro de conexão. Ligar é
`RASTRO_ATIVO=true` e apontar o endereço. Está no
[006](docs/adr/006-rastro-distribuido.md).

## Teste de carga

O teste de vazão do build mede o custo do código: roda em processo, por MockMvc, contra um
PostgreSQL embarcado.

`carga/pedidos.js` mede o custo do sistema, com k6 contra o `docker-compose`: rede de verdade,
serialização HTTP, o gateway na borda e o pool de conexões sob pressão.

```bash
docker compose up -d --wait
k6 run carga/pedidos.js
```

Roda no CI a cada push na branch principal, e não a cada pull request, porque levanta o sistema
inteiro e levaria minutos que o retorno rápido não pode pagar.

Continua não sendo um teste contra ambiente com forma de produção: é uma máquina só, com bancos
vazios. Serve como piso e como tendência, não como número de capacidade.

## Decisões de arquitetura

Cada uma tem um documento curto explicando o custo, inclusive o que se abriu mão:

- [001, a saga dentro do agregado, e não num orquestrador à parte](docs/adr/001-saga-no-agregado.md)
- [002, outbox em vez de publicar direto](docs/adr/002-outbox-em-vez-de-publicar-direto.md)
- [003, trava otimista no estoque](docs/adr/003-trava-otimista-no-estoque.md)
- [004, um banco por serviço](docs/adr/004-um-banco-por-servico.md)
- [005, PostgreSQL e Kafka embarcados nos testes](docs/adr/005-infra-embutida-nos-testes.md)
- [006, rastro distribuído que atravessa o outbox](docs/adr/006-rastro-distribuido.md)

## O modelo de leitura

Cada serviço é dono do próprio banco, e essa é a questão. O custo é que nenhum deles sozinho
responde "como está o meu pedido": o estado da saga está em pedidos, a reserva em estoque e a
cobrança em pagamentos. Até então o navegador fazia a junção, com três chamadas.

O `servico-consulta` consome os mesmos eventos do Kafka sob um grupo de consumo próprio e mantém
um documento desnormalizado por pedido no **MongoDB**. Uma leitura devolve o pedido, os itens, a
cobrança e a linha do tempo.

MongoDB em vez de um quarto PostgreSQL porque o que a tela quer *é* um documento. Em tabelas isso
vira uma junção de quatro para montar uma resposta que nunca é lida em pedaços. E a escolha é
barata de defender pelo outro lado: este dado **não** é a fonte da verdade. Ele é derivado dos
eventos e pode ser jogado fora e reconstruído a partir do tópico. A consistência forte fica no
PostgreSQL de cada serviço, onde ela é de fato imposta.

O que isso não dá é consistência imediata. A projeção segue o tópico, então existe uma janela
entre o pedido mudar de estado e a leitura refletir. Para acompanhar uma saga isso serve, e a
tela já consulta de tempos em tempos. Para decidir se um pedido ainda pode ser cancelado, quem
manda continua sendo o serviço de pedidos.

A idempotência mora no próprio documento, em `eventosAplicados`, e não no inbox da plataforma. A
projeção já precisa registrar o que aplicou para não duplicar a linha do tempo, e uma segunda
tabela de controle seria o mesmo fato guardado em dois lugares.

## Infraestrutura como código

`infra/` tem Terraform para a AWS: uma VPC com sub-redes privadas, um RDS PostgreSQL por serviço,
MSK Serverless para o Kafka e um repositório ECR por imagem.

`terraform fmt` e `terraform validate` rodam a cada build. Nenhum dos dois precisa de credencial
da AWS: eles conferem sintaxe, referência entre recursos e tipo de atributo. O que eles **não**
conferem é se os recursos existem de fato numa conta, e nada aqui foi aplicado em uma.

## Kubernetes

Manifestos simples em [`k8s/`](k8s/), aplicados com `kubectl apply -k k8s/`. Três bancos, um por
serviço, pelo mesmo motivo que o compose tem três. Duas réplicas por serviço, o que o outbox
permite porque o `SKIP LOCKED` deixa as instâncias dividirem a tabela de mensagens sem publicar
nada duas vezes.

Dois níveis de conferência rodam a cada build. O `kubeconform` valida os manifestos contra os
esquemas da API do Kubernetes, então uma `apiVersion` errada ou um campo escrito errado reprova o
pipeline em vez de reprovar o `apply`. Depois um cluster `kind` de verdade é criado e o
`kubectl apply -k k8s/` roda contra ele, esperando o corretor e os três bancos subirem. Esse
segundo passo é o que pega sonda mal configurada, pedido de recurso impossível ou seletor que não
casa, coisas que validação de esquema não enxerga.

Eles ainda assim nunca rodaram num cluster de produção, e o README do diretório diz isso.

## Stack

Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL, MongoDB, Flyway, Kafka, Redpanda,
Micrometer com Prometheus e OpenTelemetry, OpenAPI, JUnit 5, AssertJ, Awaitility, ArchUnit, k6,
Docker, Kubernetes, Terraform.

Os erros seguem a [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html), com uma extensão
`codigo` estável para o cliente poder decidir sem interpretar texto corrido.

## O que não está aqui

Dito de uma vez, em vez de descoberto depois:

- **Não há adquirente de verdade.** O `AdquirenteSimulado` fica atrás de uma interface com o
  comportamento de um real, recusas incluídas. Trocar por Cielo ou Stone é escrever uma classe.
- **Não há autenticação.** O id do cliente chega num cabeçalho. Num sistema real ele viria de um
  token validado; manter simples deixa o foco na saga.
- **Nunca rodou em cluster nem em conta da AWS de verdade.** Os manifestos do Kubernetes são
  validados contra os esquemas e aplicados num cluster `kind` descartável a cada build, e o
  Terraform passa pelo `validate`, mas nenhum dos dois foi para infraestrutura de produção.
