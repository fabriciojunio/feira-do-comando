# 006 — Rastro distribuído que atravessa o outbox

Data: 2026-09-02
Situação: aceita

## Contexto

A pergunta que o sistema não sabia responder: um pedido foi feito, demorou a ser confirmado, e
onde foi o tempo?

O que existia antes: métrica por serviço, com Micrometer e Prometheus, e um id de correlação
carregado no log. Isso responde "está lento" e permite juntar as linhas de log de um mesmo
pedido, se você souber procurar. Não responde "está lento onde", e a distância entre as duas
coisas é meia hora de investigação com quatro janelas de log abertas.

Num sistema em que o pedido atravessa pedidos, estoque, pagamentos e consulta por Kafka, essa é
a primeira pergunta de qualquer plantão.

## O problema não é ligar o rastreamento

Micrometer Tracing no Spring Boot é uma dependência e três linhas de configuração. HTTP funciona
sozinho.

O problema é o outbox, e ele é específico deste desenho.

A mensagem é gravada dentro da transação que muda o estado do pedido. É publicada depois, por
uma tarefa agendada, em outra thread, quando aquela requisição já terminou e o contexto dela já
foi descartado. Contexto de rastro vive na thread.

Sem tratar isso, o painel mostra rastros que não se conhecem: um que começa no `POST /pedidos` e
termina no commit, e outro que começa do nada meio segundo depois. O caso que interessa
investigar, que é a saga inteira, é exatamente o que se perde.

É a mesma armadilha que o outbox resolve para a entrega, reaparecendo na observabilidade: o que
atravessa transação precisa ser gravado, não guardado em memória.

## Decisão

O contexto viaja junto com a mensagem, do mesmo jeito que a carga.

**No outbox, numa coluna.** `trace_pai`, no formato `traceparent` do W3C, gravado na mesma linha
e na mesma transação do evento. Aceita nulo: evento de tarefa agendada não tem rastro anterior
para herdar, e isso é normal.

**Na rede, em cabeçalho do registro.** Fora da carga, porque a carga é o contrato entre serviços,
e quem consome um evento não deveria precisar desserializar dado de observabilidade para
entender o que aconteceu.

**No consumo, num interceptador da fábrica de consumidores.** E não em cada `@KafkaListener`. São
quatro consumidores hoje, e o quinto que alguém escrever amanhã já nasce rastreado sem saber que
observabilidade existe.

**Formato do W3C, e não invenção nossa.** É o mesmo `traceparent` que viaja em cabeçalho HTTP, o
que permite o rastro atravessar processo, broker e serviço de terceiro sem tradução no meio.

**Um trecho por consulta ao banco.** O `DataSource` é envolvido, o que pega também o que o
Hibernate emite por conta própria: carregamento tardio, conferência de versão da trava otimista,
e o caso de uma consulta virar N. Esse último é o motivo mais forte: o problema de N mais um só
aparece quando alguém vê as consultas repetidas enfileiradas no painel.

## Detalhes que custaram tempo

**O trecho do consumo abre num método e fecha em outro.** O interceptador do Spring Kafka avisa
da chegada e do fim do registro separadamente, e o container usa uma thread por partição.
Guardar o trecho num campo faria uma partição fechar o trecho da outra. Fica numa variável por
thread.

**Trecho não fechado nunca chega ao painel.** O rastro fica eternamente em andamento, e o
sintoma é confuso: o pedido aparece, mas incompleto. O fechamento vale para o caminho feliz e
para os dois de erro, e por isso está num `finally`.

**O valor dos parâmetros não vai para o trecho da consulta.** Telemetria sai da aplicação e fica
guardada em outro sistema. O texto do comando preparado basta para identificar a consulta.

**O nome do trecho da consulta é a operação e a tabela.** Painel agrupa por nome; com a consulta
inteira no nome, cada execução vira um grupo de uma só e a pergunta "qual consulta está lenta no
geral" deixa de ter resposta.

## Desligado por padrão

`management.tracing.enabled` vem `false`, e ligar exige apontar o coletor.

Não é medo de custo, é do log: exportador sem destino tenta conectar, falha e reclama, num laço,
para sempre.

## O que se ganha e o que não

Ganha-se a linha do tempo da saga: requisição, gravação, publicação, consumo em cada serviço,
consultas ao banco dentro de cada um, com duração e o erro marcado onde aconteceu.

Não substitui a métrica. Rastro amostrado responde sobre um pedido; métrica responde sobre
todos. Quem tem só rastro descobre que o caso que ficou fora da amostra era o que importava.

E não resolve a análise de causa raiz sozinho. Ele diz onde o tempo foi, não por quê.
