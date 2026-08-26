# ADR 002: Outbox transacional em vez de publicar direto

**Data:** 2026-08-26
**Status:** Aceito

## Contexto

Criar um pedido significa gravar no banco e avisar os outros serviços. São dois
sistemas diferentes e não existe transação que cubra os dois.

Publicando primeiro e gravando depois, uma queda no meio deixa um evento na
fila para um pedido que não existe: o estoque reserva mercadoria para um pedido
fantasma. Gravando primeiro e publicando depois, a queda deixa um pedido que
ninguém foi avisado: o cliente vê "processando" para sempre.

Os dois casos são raros e os dois acontecem. Em volume, raro é uma vez por
semana.

## Decisão

A mensagem é gravada numa tabela `outbox` **dentro da mesma transação** da
mudança de estado. Um processo agendado lê a tabela e publica no Kafka.

Detalhes que não são acidentais:

- **Publica primeiro, marca depois.** O contrário perderia mensagem se o
  processo caísse entre as duas coisas.
- **Envio síncrono**, com `get()` no future. Assíncrono marcaria como publicada
  uma mensagem cujo envio ainda podia falhar.
- **`SELECT ... FOR UPDATE SKIP LOCKED`** na busca do lote. É o que permite
  várias instâncias publicarem em paralelo sem duplicar: a segunda pula o que a
  primeira pegou em vez de esperar o lock.
- **Recuo exponencial com teto de cinco minutos**, e dez tentativas antes de a
  mensagem ficar marcada como travada para análise manual.
- **Métrica do número de pendentes.** Outbox crescendo é o primeiro sinal de
  broker com problema, e aparece antes de qualquer reclamação de cliente.

## Consequências positivas

- Não existe estado gravado sem evento correspondente, nem o contrário.
- Uma queda do Kafka não derruba a API: os pedidos continuam entrando e a fila
  drena quando o broker volta.
- A tabela é uma trilha do que foi publicado, útil em investigação.

## Consequências negativas

- **Troca perda por duplicata.** Uma mensagem pode ser publicada duas vezes se
  o processo cair entre publicar e marcar. Isso obriga todo consumidor a ser
  idempotente, e é a razão de existir o inbox.
- **Latência extra.** O evento sai em até meio segundo, e não no instante do
  commit. Para pedido isso é irrelevante; para algo que exigisse milissegundos,
  não serviria.
- **Mais uma tabela quente**, que cresce na velocidade dos pedidos e precisa de
  expurgo. Há uma rotina diária para isso.
- **Ordem não é garantida entre tópicos diferentes.** Dentro do mesmo pedido
  ela é, porque a chave de partição é o id do pedido.

## Alternativas consideradas

**Publicar direto, aceitando o risco.** Funciona até a primeira queda no
momento errado, e o defeito aparece como divergência de dados, que é o tipo
mais caro de investigar.

**Debezium lendo o log de replicação.** Elimina a consulta periódica e é a
evolução natural quando o volume crescer. Custa um Kafka Connect para operar, o
que triplicaria a infraestrutura deste projeto.

**Transação distribuída com duas fases.** O Kafka não participa de XA de forma
útil, e 2PC traz travamento e um coordenador que vira ponto único de falha.
