#!/usr/bin/env bash
# Sobe o broker fechado (TLS, SASL/SCRAM e ACL), cria usuários e permissões,
# tenta os acessos que não deveriam passar e roda um pedido de ponta a ponta
# com os quatro serviços usando só o que cada um precisa.
# Uso, da raiz do repositório: bash infra/kafka/provar.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.seguro.yml)
CERTS=infra/kafka/certs
falhas=0

conferir() {
  if [ "$1" = 0 ]; then echo "ok     $2"; else echo "FALHOU $2"; falhas=$((falhas + 1)); fi
}

# recusado PADRÃO comando...: passa só se o comando falhar E a saída trouxer o
# motivo esperado. Falhar por outro motivo (ambiente quebrado, comando errado)
# não conta como ataque barrado.
recusado() {
  local padrao=$1; shift
  if "$@" > /tmp/saida.txt 2>&1; then cat /tmp/saida.txt; return 1; fi
  echo "       motivo: $(grep -v '^\s*$' /tmp/saida.txt | tail -1 | cut -c1-150)"
  grep -qiE "$padrao" /tmp/saida.txt
}

# verificar "descrição" comando...: registra o resultado sem parar o script.
verificar() {
  local descricao=$1; shift
  if "$@"; then conferir 0 "$descricao"; else conferir 1 "$descricao"; fi
}

derrubar() {
  if [ "${1:-0}" != 0 ]; then "${COMPOSE[@]}" logs --no-color --tail=60 || true; fi
  "${COMPOSE[@]}" down -v --remove-orphans || true
  rm -rf "$CERTS"
}
trap 'derrubar $?' EXIT

# Senhas novas a cada execução.
for usuario in ADMIN PEDIDOS ESTOQUE PAGAMENTOS CONSULTA; do
  export "FEIRA_KAFKA_SENHA_$usuario=$(openssl rand -hex 24)"
done

# CA e certificado do broker de vida curta, só para esta execução.
mkdir -p "$CERTS"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 \
  -subj "/CN=CA da Feira (teste)" -keyout "$CERTS/ca.key" -out "$CERTS/ca.crt" 2>/dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -subj "/CN=broker" -keyout "$CERTS/broker.key" -out "$CERTS/broker.csr" 2>/dev/null
openssl x509 -req -in "$CERTS/broker.csr" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" -CAcreateserial \
  -days 2 -out "$CERTS/broker.crt" \
  -extfile <(printf "subjectAltName=DNS:broker,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth") 2>/dev/null
# O broker roda com usuário próprio dentro do contêiner e precisa ler a chave.
chmod 644 "$CERTS"/*
rm -f "$CERTS/ca.key" "$CERTS/broker.csr"

"${COMPOSE[@]}" up -d --wait --wait-timeout 180 broker
# Opções explícitas: dentro do contêiner, as variáveis RPK_* não bastaram para o
# rpk fazer SASL (o broker respondia ILLEGAL_SASL_STATE).
CONEXAO=(-X sasl.mechanism=SCRAM-SHA-256 -X tls.enabled=true -X tls.ca=/certs/ca.crt)
rpk() { "${COMPOSE[@]}" exec -T broker rpk "$@" -X user=admin -X pass="$FEIRA_KAFKA_SENHA_ADMIN" "${CONEXAO[@]}"; }
echo "Diagnóstico do rpk no broker:"
"${COMPOSE[@]}" exec -T broker bash -c 'env | grep ^RPK_ | sed "s/^RPK_PASS=.*/RPK_PASS=***/"; rpk profile print 2>&1 | grep -vi pass' || true

echo "Usuários, tópicos e permissões"
rpk security user create admin -p "$FEIRA_KAFKA_SENHA_ADMIN" --mechanism SCRAM-SHA-256 > /dev/null
for servico in pedidos estoque pagamentos consulta; do
  senha_var="FEIRA_KAFKA_SENHA_${servico^^}"
  rpk security user create "$servico" -p "${!senha_var}" --mechanism SCRAM-SHA-256 > /dev/null
done
# O usuário nasce pela API de administração e leva um instante para valer no
# Kafka; antes disso a autenticação falha com ILLEGAL_SASL_STATE.
for _ in $(seq 1 30); do rpk topic list > /dev/null 2>&1 && break; sleep 1; done
rpk topic list > /dev/null
# Com o admin criado, a API de administração passa a exigir senha.
rpk cluster config set admin_api_require_auth true > /dev/null

for topico in feira.pedidos feira.estoque feira.pagamentos; do
  rpk topic create "$topico" "$topico.dlq" -p 3 > /dev/null
done

permitir() { # usuário operação recurso...
  local usuario=$1 operacao=$2; shift 2
  rpk security acl create --allow-principal "User:$usuario" --operation "$operacao" "$@" > /dev/null
}
# Cada serviço escreve no próprio tópico e nas filas de mortas dos tópicos que
# consome, e lê só o que consome. Ninguém cria, apaga nem altera tópico.
permitir pedidos write --topic feira.pedidos --topic feira.estoque.dlq --topic feira.pagamentos.dlq
permitir pedidos read --topic feira.estoque --topic feira.pagamentos --group feira-pedidos
permitir estoque write --topic feira.estoque --topic feira.pedidos.dlq
permitir estoque read --topic feira.pedidos --group feira-estoque
permitir pagamentos write --topic feira.pagamentos --topic feira.pedidos.dlq --topic feira.estoque.dlq
permitir pagamentos read --topic feira.pedidos --topic feira.estoque --group feira-pagamentos
permitir consulta write --topic feira.pedidos.dlq --topic feira.estoque.dlq --topic feira.pagamentos.dlq
permitir consulta read --topic feira.pedidos --topic feira.estoque --topic feira.pagamentos --group consulta
# Produtor idempotente (enable.idempotence) pede esta permissão no cluster.
for servico in pedidos estoque pagamentos consulta; do
  permitir "$servico" idempotent_write --cluster
done

# O timeout fica aqui dentro porque não executa função do shell. Ele cobre o
# caso em que a permissão vazou e o consumo fica esperando mensagem.
como() { # usuário senha comando...
  local usuario=$1 senha=$2; shift 2
  timeout 30 "${COMPOSE[@]}" exec -T broker rpk "$@" -X user="$usuario" -X pass="$senha" "${CONEXAO[@]}"
}
publica_como() { # usuário senha
  echo controle | como "$1" "$2" topic produce feira.pedidos -k controle
}

echo
echo "Acessos que precisam passar (controle: sem isso, as recusas abaixo não provam nada)"
verificar "pedidos publica no próprio tópico, com TLS e SASL" publica_como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS"
le_pedidos() { como estoque "$FEIRA_KAFKA_SENHA_ESTOQUE" topic consume feira.pedidos -n 1 --group feira-estoque -o start | grep -q controle; }
verificar "estoque lê o tópico de pedidos com o grupo dele" le_pedidos

echo
echo "Acessos que não podem passar"
NEGADO="AUTHORIZATION_FAILED"
verificar "estoque não publica no tópico de pedidos" \
  recusado "$NEGADO" publica_como estoque "$FEIRA_KAFKA_SENHA_ESTOQUE"
verificar "consulta, que só lê, não publica em tópico nenhum de negócio" \
  recusado "$NEGADO" publica_como consulta "$FEIRA_KAFKA_SENHA_CONSULTA"
verificar "estoque não lê o tópico de pagamentos" \
  recusado "$NEGADO" como estoque "$FEIRA_KAFKA_SENHA_ESTOQUE" topic consume feira.pagamentos -n 1 --group feira-estoque -o start
verificar "pedidos não entra no grupo de consumo do estoque" \
  recusado "$NEGADO" como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic consume feira.estoque -n 1 --group feira-estoque -o start
verificar "serviço não cria tópico" \
  recusado "$NEGADO" como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic create feira.invasor
# O rpk topic delete sai com código 0 mesmo quando o broker nega; o que prova
# é a negação na saída e o tópico continuar existindo depois da tentativa.
apagar_e_conferir() {
  como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic delete feira.pagamentos > /tmp/saida.txt 2>&1 || true
  echo "       resposta: $(tr '\n' ' ' < /tmp/saida.txt | cut -c1-150)"
  grep -q "$NEGADO" /tmp/saida.txt && rpk topic describe feira.pagamentos > /dev/null
}
verificar "serviço não apaga tópico de outro (e o tópico continua lá)" apagar_e_conferir
verificar "senha errada é recusada" \
  recusado "SASL|authentication" como pedidos "senha-errada" topic describe feira.pedidos
verificar "conexão sem SASL é recusada" \
  recusado "SASL" timeout 30 "${COMPOSE[@]}" exec -T broker env -u RPK_USER -u RPK_PASS -u RPK_SASL_MECHANISM \
  rpk topic describe feira.pedidos -X tls.enabled=true -X tls.ca=/certs/ca.crt
# RPK_TLS_CA no ambiente liga o TLS sozinho; para testar sem TLS é preciso tirá-lo.
verificar "conexão sem TLS é recusada" \
  recusado "closed|EOF|reset|timeout|unable" timeout 30 "${COMPOSE[@]}" exec -T broker env -u RPK_TLS_ENABLED -u RPK_TLS_CA \
  rpk topic describe feira.pedidos -X user=admin -X pass="$FEIRA_KAFKA_SENHA_ADMIN" -X sasl.mechanism=SCRAM-SHA-256
admin_sem_senha() {
  "${COMPOSE[@]}" exec -T broker bash -c 'exec 3<>/dev/tcp/127.0.0.1/9644
    printf "GET /v1/security/users HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; head -1 <&3' \
    | tee /dev/stderr | grep -qE " 40[13] "
}
verificar "API de administração sem senha responde 401 ou 403" admin_sem_senha
verificar "API de administração não tem porta publicada" \
  recusado "refused" bash -c "exec 3<>/dev/tcp/127.0.0.1/9644"
certificado_valido() {
  echo | openssl s_client -connect 127.0.0.1:9092 -CAfile "$CERTS/ca.crt" -servername localhost \
    -verify_hostname localhost > /tmp/tls.txt 2>&1 || true
  grep -E "subject=|issuer=|Verify return code|verify error" /tmp/tls.txt | sed 's/^/       /'
  grep -q "Verify return code: 0 (ok)" /tmp/tls.txt
}
verificar "o broker apresenta certificado válido da CA da Feira, para o nome certo" certificado_valido

echo
echo "Pedido de ponta a ponta com os serviços autenticados"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 300 \
  banco-pedidos banco-estoque banco-pagamentos mongo \
  servico-pedidos servico-estoque servico-pagamentos servico-consulta

curl -fsS -X POST http://localhost:8082/api/v1/estoque/itens -H 'Content-Type: application/json' \
  -d '{"sku":"PROVA-KAFKA","descricao":"Produto da prova (dados simulados)","quantidade":10}' > /dev/null
cliente=$(cat /proc/sys/kernel/random/uuid)
id=$(curl -fsS -X POST http://localhost:8081/api/v1/pedidos -H 'Content-Type: application/json' \
  -H "X-Cliente-Id: $cliente" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"itens":[{"sku":"PROVA-KAFKA","descricao":"Produto da prova","quantidade":1,"precoUnitario":"29.90"}]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
status=""
for _ in $(seq 1 60); do
  status=$(curl -fsS "http://localhost:8081/api/v1/pedidos/$id" -H "X-Cliente-Id: $cliente" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  [ "$status" = CONFIRMADO ] && break
  sleep 2
done
[ "$status" = CONFIRMADO ] || { echo "       tópicos no broker:"; rpk topic list 2>&1 | sed 's/^/       /'; }
verificar "o pedido passou por estoque e pagamento e foi confirmado (status: $status)" [ "$status" = CONFIRMADO ]

projetado=1
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null "http://localhost:8084/api/v1/consulta/pedidos/$id" -H "X-Cliente-Id: $cliente" && { projetado=0; break; }
  sleep 2
done
conferir $projetado "a consulta recebeu os eventos e montou o pedido"

negadas=$("${COMPOSE[@]}" logs servico-pedidos servico-estoque servico-pagamentos servico-consulta 2>&1 \
  | grep -ciE "AuthorizationException|Not authorized" || true)
verificar "nenhum serviço teve acesso negado no caminho normal ($negadas ocorrências)" [ "$negadas" = 0 ]

echo
if [ "$falhas" != 0 ]; then echo "$falhas verificação(ões) falharam"; exit 1; fi
echo "Todas as verificações passaram."
