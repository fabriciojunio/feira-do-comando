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

# Executa e devolve 0 se o comando FALHOU, que é o esperado nos ataques. O
# timeout cobre o caso em que a permissão vazou e o consumo fica esperando.
recusado() {
  if timeout 30 "$@" > /tmp/saida.txt 2>&1; then cat /tmp/saida.txt; return 1; fi
  echo "       motivo: $(grep -v '^\s*$' /tmp/saida.txt | tail -1 | cut -c1-140)"
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
rpk() { "${COMPOSE[@]}" exec -T broker rpk "$@"; }

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

como() { # usuário senha comando...
  local usuario=$1 senha=$2; shift 2
  "${COMPOSE[@]}" exec -T broker rpk "$@" -X user="$usuario" -X pass="$senha"
}

echo
echo "Acessos que precisam passar (controle: sem isso, as recusas abaixo não provam nada)"
publica_pedidos() { echo controle | "${COMPOSE[@]}" exec -T broker rpk topic produce feira.pedidos -k controle   -X user=pedidos -X pass="$FEIRA_KAFKA_SENHA_PEDIDOS" > /dev/null; }
verificar "pedidos publica no próprio tópico, com TLS e SASL" publica_pedidos
le_pedidos() { como estoque "$FEIRA_KAFKA_SENHA_ESTOQUE" topic consume feira.pedidos -n 1 --group feira-estoque -o start   | grep -q controle; }
verificar "estoque lê o tópico de pedidos com o grupo dele" le_pedidos

echo
echo "Acessos que não podem passar"
verificar "estoque não publica no tópico de pedidos" recusado bash -c "echo x | ${COMPOSE[*]} exec -T broker rpk topic produce feira.pedidos -X user=estoque -X pass=$FEIRA_KAFKA_SENHA_ESTOQUE"
verificar "consulta, que só lê, não publica em tópico nenhum de negócio" recusado bash -c "echo x | ${COMPOSE[*]} exec -T broker rpk topic produce feira.pedidos -X user=consulta -X pass=$FEIRA_KAFKA_SENHA_CONSULTA"
verificar "estoque não lê o tópico de pagamentos" recusado como estoque "$FEIRA_KAFKA_SENHA_ESTOQUE" topic consume feira.pagamentos -n 1 --group feira-estoque -o start
verificar "pedidos não entra no grupo de consumo do estoque" recusado como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic consume feira.estoque -n 1 --group feira-estoque -o start
verificar "serviço não cria tópico" recusado como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic create feira.invasor
verificar "serviço não apaga tópico de outro" recusado como pedidos "$FEIRA_KAFKA_SENHA_PEDIDOS" topic delete feira.pagamentos
verificar "senha errada é recusada" recusado como pedidos "senha-errada" topic describe feira.pedidos
verificar "conexão sem SASL é recusada" recusado "${COMPOSE[@]}" exec -T broker env -u RPK_USER -u RPK_PASS -u RPK_SASL_MECHANISM rpk topic describe feira.pedidos
verificar "conexão sem TLS é recusada" recusado "${COMPOSE[@]}" exec -T broker rpk topic describe feira.pedidos -X tls.enabled=false
verificar "API de administração sem senha é recusada" recusado "${COMPOSE[@]}" exec -T broker env -u RPK_USER -u RPK_PASS rpk security user list
verificar "API de administração não tem porta publicada" recusado bash -c "exec 3<>/dev/tcp/127.0.0.1/9644"
certificado_valido() {
  echo | openssl s_client -connect localhost:9092 -CAfile "$CERTS/ca.crt" -servername localhost 2>/dev/null     | grep -q "Verify return code: 0 (ok)"
}
verificar "o broker apresenta certificado válido para a CA da Feira" certificado_valido

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
