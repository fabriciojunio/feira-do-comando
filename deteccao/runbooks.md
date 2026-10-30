# Runbooks de detecção

*Dados simulados nas amostras. Regras em `sigma/`, testadas em `test_regras_sigma.py`.*

Cada regra Sigma aponta para a seção dela aqui. O runbook diz o que o alerta significa, como
confirmar em poucos minutos e o que fazer. Alerta sem runbook vira alerta que ninguém sabe tratar.

## feira-mensagem-ilegivel-no-kafka

**Severidade:** alta. **ATT&CK:** T1195 (comprometimento da cadeia de fornecimento), porque a
origem provável é um produtor alterado.

**O que significa.** Um consumidor recebeu do broker uma mensagem fora do contrato e a desviou
para a fila de mensagens mortas. Os serviços só publicam pelo outbox, com o contrato validado;
mensagem ilegível vem de produtor com defeito ou de alguém com acesso ao broker.

**Confirmar.**
1. Pegar o tópico e a chave na própria linha do log.
2. Ler a mensagem na fila de mortas (`<tópico>.dlq`) e comparar com o contrato em `contratos/`.
3. Conferir se houve deploy de algum produtor no mesmo intervalo.

**Responder.** Se não houve deploy: tratar como incidente. Revogar a credencial do produtor que
publicou, conferir as ACLs do broker e preservar a mensagem como evidência. Se houve deploy:
reverter o produtor e reprocessar a fila de mortas depois da correção.

## feira-requisicao-ilegivel

**Severidade:** baixa. **ATT&CK:** T1595.002 (varredura de vulnerabilidade).

**O que significa.** O corpo da requisição não é JSON válido ou tem tipo errado. Uma ocorrência
isolada é erro de cliente. Dezenas em poucos minutos, do mesmo IP, é ferramenta de varredura ou
fuzzing.

**Confirmar.** Agrupar por IP de origem no proxy, numa janela de 5 minutos. Mais de 20 do mesmo
IP confirma varredura.

**Responder.** Bloquear o IP na borda (WAF ou gateway) por 24 horas. Conferir no mesmo intervalo
se houve `feira-erro-inesperado`: varredura que acha erro não tratado é o próximo passo de um
ataque.

## feira-erro-inesperado

**Severidade:** média. **ATT&CK:** T1190 (exploração de aplicação exposta).

**O que significa.** Uma exceção que nenhum tratador previu. O cliente recebe resposta genérica
e o identificador entre colchetes liga o relato do usuário ao rastro completo no log.

**Confirmar.** Buscar o identificador no log para ler a exceção inteira e o rastro distribuído
(`correlationId`). Ver se a entrada que causou o erro foi montada para isso: payload estranho,
cabeçalho fora do padrão, sequência vinda de uma varredura.

**Responder.** Se a entrada foi montada: tratar como tentativa de exploração, abrir achado com o
payload e corrigir o tratamento. Se foi defeito comum: abrir bug com o rastro. Nos dois casos,
acrescentar teste com a entrada que derrubou.

## feira-mensagem-travada-no-outbox

**Severidade:** média. **ATT&CK:** T1499 (negação de serviço no endpoint).

**O que significa.** Uma mensagem esgotou as tentativas de publicação. O pedido dela fica parado
até alguém agir. Uma só costuma ser mensagem com defeito; várias ao mesmo tempo indicam broker
fora do ar ou recusando conexão.

**Confirmar.** Contar quantas travaram nos últimos 15 minutos e conferir a saúde do broker
(`/actuator/health` dos serviços mostra o Kafka).

**Responder.** Broker fora: restabelecer e liberar as mensagens travadas para nova tentativa.
Broker recusando por autenticação ou ACL: tratar como possível alteração indevida de
configuração, conferir quem mudou e quando.
