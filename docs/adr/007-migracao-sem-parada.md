# 007 — Migração de esquema sem derrubar a versão anterior

Data: 2026-09-02
Situação: aceita

## Contexto

Os manifestos em `k8s/` sobem duas réplicas por serviço, com atualização gradual e um
PodDisruptionBudget que não deixa as duas caírem juntas. É isso que dá a implantação sem parada,
e é isso que cria o problema deste documento.

Durante alguns segundos a cada implantação, a versão nova e a antiga do mesmo serviço estão
rodando ao mesmo tempo, contra o mesmo banco. O Flyway roda a migração quando a primeira
instância nova sobe. Se essa migração apaga uma coluna que a versão antiga ainda lê, a janela de
convivência vira erro para quem estiver comprando naquele instante.

Aqui isso é pior que o normal por causa do outbox. O despachante da versão antiga continua lendo
a tabela `outbox` durante a troca, e uma coluna que sumiu no meio do caminho não derruba só a
requisição de alguém: derruba a publicação do evento, e o pedido fica preso entre reservado e
confirmado até alguém notar.

É um problema que não aparece em desenvolvimento, onde só existe uma versão do código rodando.
Aparece na primeira implantação depois de alguém escrever a migração.

## Decisão

Mudança de esquema incompatível acontece em três implantações separadas.

**Expandir.** A coluna nova entra ao lado da antiga, aceitando nulo. O código passa a escrever
nas duas e a ler ainda da antiga. Nada quebra, porque nada foi tirado.

**Migrar.** Os dados antigos são copiados para a coluna nova, e o código passa a ler da nova. A
antiga continua lá, ainda sendo escrita, o que é o que permite voltar atrás sem perder o que
entrou no meio do caminho.

**Contrair.** Só depois de a versão anterior não existir mais em ambiente nenhum, a coluna antiga
sai. É aqui que o comando destrutivo entra, e é o único lugar onde ele é seguro.

A coluna `trace_pai` do documento 006 é um exemplo do primeiro passo, e é por isso que ela aceita
nulo mesmo sendo escrita sempre.

## O que garante isso

Processo escrito em documento é esquecido. O que segura é uma regra no build.

`MigracaoSemQuebraTest`, no módulo `plataforma`, lê todos os arquivos de migração dos três
serviços e reprova comando destrutivo: apagar coluna, renomear coluna, trocar o tipo e apertar
para não nulo. Os quatro quebram a versão anterior enquanto ela ainda roda.

Ele mora na plataforma, e não em cada serviço, porque a regra é a mesma para os três e porque
serviço novo passa a nascer coberto sem ninguém lembrar de copiar o teste.

O teste não impede o terceiro passo, que é legítimo. Ele exige que quem o escreve declare no
próprio arquivo:

```sql
-- contrair: a versão 2.1 saiu de todos os ambientes em 02/09/2026
alter table outbox drop column coluna_antiga;
```

A marca precisa de um motivo na mesma linha. Sem essa exigência ela viraria um comentário colado
para o build passar, o que é pior que não ter regra: dá a impressão de proteção sem proteger.

## O que isto não cobre

Não cobre migração de dados demorada. Copiar milhões de linhas dentro do Flyway trava a subida da
aplicação e o Kubernetes mata o pod pela sonda de vida. Nesse caso o passo dois precisa ser um
trabalho em lotes, fora da migração, e não há nada aqui que force isso.

Não cobre a decisão de quando a versão anterior realmente saiu de circulação. Isso é operação, e
depende de olhar o que está rodando, não o repositório.

E não cobre índice criado sem `concurrently`, que trava escrita na tabela enquanto é construído.
Distinguir tabela grande de tabela pequena não dá para fazer lendo o arquivo, e uma regra que
reprova todo índice seria ignorada em uma semana.

## Consequência boa que não era o objetivo

Cada serviço tem o próprio banco, e por isso cada um numera as migrações a partir de V1 sem
consultar os outros. Isso já valia antes, mas passa a ser uma vantagem explícita: o Flyway valida
a ordem dentro de um banco só, e bancos separados nunca cruzam numeração. Sistema de banco único
com faixas por serviço não tem essa sorte.
