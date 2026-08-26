# ADR 001: A saga mora dentro do agregado

**Data:** 2026-08-26
**Status:** Aceito

## Contexto

Uma saga precisa de estado: em que passo o pedido está, o que já foi
compensado, o que falta. A forma mais divulgada é um orquestrador separado, com
tabela própria de instâncias de saga, normalmente usando uma biblioteca de
máquina de estados.

## Decisão

O estado da saga **é** o estado do pedido, e mora em `Pedido`. O método
`processar(Evento, Instant)` recebe um evento e devolve uma `Decisao`, que é um
valor: diz o que deve acontecer, sem publicar nem gravar nada.

## Consequências positivas

- **Uma fonte de verdade.** Orquestrador separado cria duas tabelas que
  precisam concordar sobre o mesmo pedido. Elas eventualmente discordam, e
  descobrir qual das duas está certa é trabalho de madrugada.
- **A máquina de estados é testável sem infraestrutura.** Os 36 casos de
  `SagaDoPedidoTest` rodam em menos de um segundo, sem Spring, sem banco e sem
  broker. Foi isso que permitiu cobrir o que quase ninguém cobre: evento
  repetido, fora de ordem, atrasado, e a corrida entre aprovação de pagamento e
  cancelamento.
- **O `switch` sobre a interface selada não tem `default`.** Evento novo no
  contrato faz o código parar de compilar. Em sistema distribuído, evento novo
  que ninguém trata é falha silenciosa.
- **A decisão é um valor, não um efeito.** Quem executa é a camada de
  aplicação, dentro de uma transação. Isso mantém o domínio livre de Kafka.

## Consequências negativas

- **Não serve para saga que atravessa vários agregados.** Se um dia houver um
  fluxo coordenando pedido, assinatura e entrega ao mesmo tempo, esse estado não
  pertence a nenhum dos três e um orquestrador próprio passa a fazer sentido.
- **O agregado fica maior.** `Pedido` tem umas trezentas linhas, boa parte
  delas a saga. É aceitável enquanto for um fluxo só.
- **Sem visualização pronta.** Ferramentas como Camunda dão o diagrama da
  instância de graça. Aqui, entender onde o pedido parou exige ler o status e a
  trilha de eventos.

## Alternativas consideradas

**Orquestrador separado com tabela de saga.** Padrão consagrado e a escolha
certa quando a saga coordena agregados diferentes. Aqui traria duas fontes de
verdade sem resolver problema nenhum.

**Coreografia pura, sem orquestrador.** Cada serviço reage ao evento do outro e
ninguém tem visão do todo. Simples no começo e difícil de depurar depois:
descobrir por que um pedido travou exigiria ler o log de três serviços e montar
a ordem na mão.

**Motor de execução durável, como Temporal.** Resolve retentativa, timeout e
estado com muito mais robustez que este código. Descartado por peso: um
servidor a mais para operar, e o projeto passaria a demonstrar a ferramenta em
vez do padrão.
