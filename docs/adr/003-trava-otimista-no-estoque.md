# ADR 003: Trava otimista no estoque

**Data:** 2026-08-26
**Status:** Aceito

## Contexto

Dois pedidos simultâneos podem disputar a última unidade de um SKU. Sem
controle, os dois leem saldo 1, os dois reservam, e a loja vende duas vezes a
mesma peça. O cliente descobre no dia da entrega.

## Decisão

Trava otimista com `@Version` no `ItemDeEstoque`. Os dois pedidos leem a mesma
versão; o primeiro a gravar avança a versão, e o segundo recebe
`OptimisticLockingFailureException` do banco em vez de sobrescrever.

Junto disso, três decisões de modelagem:

- **Três saldos separados**: disponível, reservado e vendido. Reserva move
  unidade entre colunas, e não para fora do estoque, o que permite compensar
  sem inventar mercadoria.
- **Tudo ou nada por pedido.** Se um SKU faltar, o que já tinha sido separado na
  mesma tentativa é devolvido. Reserva parcial deixaria o cliente com meio
  pedido e o galpão com mercadoria presa.
- **SKUs percorridos em ordem alfabética.** Dois pedidos com os mesmos itens em
  ordens diferentes travariam um ao outro; ordenar antes de tocar no banco é a
  receita padrão contra impasse.

## Consequências positivas

- **Nada de lock em disputa que não existe.** A maioria dos pedidos toca SKUs
  diferentes, e travar linha em toda reserva serializaria o catálogo inteiro por
  causa de uma minoria de casos.
- **O conflito é explícito.** A exceção chega ao consumidor, o Kafka reentrega,
  e a nova tentativa lê o saldo já atualizado.
- **Testável de verdade.** `ConcorrenciaDeReservaTest` sobe dez threads
  disputando três unidades num PostgreSQL real e confere que o galpão continua
  com três unidades no fim. Concorrência não se testa com dublê: mock responde
  na ordem que o teste mandar.

## Consequências negativas

- **Em disputa alta, há retrabalho.** Lançamento com estoque pequeno e muita
  gente ao mesmo tempo gera várias tentativas perdidas, e cada uma custa uma ida
  ao banco.
- **Exige que o chamador saiba repetir.** Aqui quem repete é o Kafka, o que
  resolve. Numa chamada HTTP direta, o cliente receberia 409 e teria que tentar
  de novo.
- **Não protege contra reserva concorrente do mesmo pedido**; para isso o que
  vale é a chave primária da reserva ser o id do pedido.

## Alternativas consideradas

**Trava pessimista, com `SELECT ... FOR UPDATE`.** Garante o mesmo resultado sem
retrabalho, e é a escolha certa quando a disputa é a regra e não a exceção, como
em leilão ou venda de ingresso. Aqui serializaria o catálogo por causa de casos
raros.

**Decremento atômico em SQL**, com `update ... set disponivel = disponivel - :q
where sku = :sku and disponivel >= :q`. É mais rápido e resolve a corrida sem
retrabalho nenhum. Ficou de fora porque tiraria a regra de dentro da entidade e
a espalharia numa consulta, e porque o mesmo update precisaria ser repetido para
liberar e para baixar.

**Redis como contador.** Rápido e errado para dinheiro: sem transação junto do
banco, o saldo do Redis e o do Postgres divergem na primeira falha.
