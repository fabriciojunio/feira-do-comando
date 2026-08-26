# ADR 004: Um banco por serviço

**Data:** 2026-08-26
**Status:** Aceito

## Contexto

Três serviços precisam de persistência. O caminho mais barato seria um
PostgreSQL com três esquemas, ou até um esquema só com prefixo de tabela.

## Decisão

Um banco por serviço, com credenciais próprias. Nenhum serviço enxerga a tabela
do outro.

## Consequências positivas

- **É o que torna o outbox necessário.** Com banco compartilhado, o serviço de
  pedidos poderia gravar direto na tabela do estoque dentro da mesma transação,
  e todo o desenho de mensageria viraria teatro. A separação é o que obriga a
  conversa a ser por evento.
- **Cada serviço muda o próprio esquema sem coordenar migração com ninguém.**
  Foi assim que `bloqueado_por_direito` e `versao` entraram em tabelas
  diferentes em momentos diferentes, sem travar o build de ninguém.
- **A falha fica contida.** Um `select` mal escrito no estoque não derruba o
  banco que atende a criação de pedidos.
- **O limite fica visível no código.** Para saber o que o pagamento sabe sobre
  o pedido, basta olhar a tabela `cobrancas`: só o valor. Com banco comum, a
  resposta seria "tudo", e ninguém saberia dizer o que pode mudar sem quebrar
  quem.

## Consequências negativas

- **Não existe `join` entre serviços.** Um relatório com pedido, reserva e
  cobrança na mesma tabela não é possível por consulta. Ou se consome os
  eventos numa base de leitura própria, ou se junta na aplicação.
- **Duplicação deliberada de dado.** O valor do pedido existe em `pedidos` e em
  `cobrancas`. É cópia proposital, com a semântica de "o valor no momento em
  que a cobrança foi registrada", mas ainda é dado repetido.
- **Três bancos para operar**, com três backups e três monitoramentos. Em
  produção isso custa dinheiro.
- **Consistência é eventual.** Logo após criar o pedido, o pagamento ainda não
  sabe dele. O código trata isso explicitamente com
  `Falhas.AguardandoDadoAnterior`, que faz o Kafka reentregar.

## Alternativas consideradas

**Um banco com três esquemas.** Mais barato de operar e suficiente para o
volume deste projeto. Descartado porque removeria a razão de ser da
arquitetura: seria um monólito com três processos.

**Monólito modular com um banco só.** Honestamente a escolha certa para a
maioria dos sistemas desse tamanho, e vale dizer isso em vez de fingir que
microsserviço é sempre melhor. Aqui o objetivo é justamente exercitar o que só
aparece quando os processos são separados: entrega ao menos uma vez, ordem
parcial, compensação.
