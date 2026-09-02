// Teste de carga do fluxo de pedido, ponta a ponta.
//
// O que ele mede que o teste de vazao do build nao mede: rede de verdade,
// serializacao HTTP, o gateway na borda e o pool de conexoes sob pressao. O
// teste em processo mede o custo do codigo; este aqui mede o custo do sistema.
//
// O que ele continua nao sendo: um teste contra ambiente com forma de
// producao. Roda contra o docker-compose, numa maquina so, com bancos sem
// dado nenhum. Serve como piso e como tendencia, e nao como capacidade.
//
//   k6 run carga/pedidos.js
//   k6 run -e BASE=http://localhost:8080 -e VUS=20 carga/pedidos.js

import http from 'k6/http';
import { check, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE || 'http://localhost:8081';
const ESTOQUE = __ENV.ESTOQUE || 'http://localhost:8082';
const VUS = Number(__ENV.VUS || 10);

const pedidosCriados = new Counter('pedidos_criados');
const pedidosRejeitados = new Counter('pedidos_rejeitados');
const duracaoDaCriacao = new Trend('duracao_da_criacao', true);

export const options = {
  scenarios: {
    // Sobe a carga em degraus em vez de tudo de uma vez. Carga instantanea
    // mede a partida a frio do pool e do JIT, e nao o sistema em regime.
    subida: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: VUS },
        { duration: '45s', target: VUS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // Limites deliberadamente frouxos: o objetivo e pegar regressao grosseira
    // no build, e nao afirmar um numero de capacidade que este ambiente nao
    // consegue sustentar.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{cenario:criacao}': ['p(95)<1500'],
    pedidos_criados: ['count>100'],
  },
};

const SKU = 'CARGA-K6';

// Estoque alto de proposito: falta de estoque e um caminho de negocio valido,
// e nao um erro, mas misturar os dois faria a taxa de falha medir a coisa
// errada.
export function setup() {
  const resposta = http.post(`${ESTOQUE}/api/v1/estoque/itens`, JSON.stringify({
    sku: SKU,
    descricao: 'Produto do teste de carga',
    quantidade: 1000000,
  }), { headers: { 'Content-Type': 'application/json' } });

  // 200 quando o SKU ja existe: repor soma ao saldo em vez de sobrescrever, e
  // o teste rodando duas vezes no mesmo ambiente cai nesse caminho.
  const preparado = check(resposta, {
    'estoque preparado': (r) => r.status === 201 || r.status === 200,
  });

  // Sem estoque, todo pedido e recusado e a medicao vira 100% de falha sem
  // dizer por que. Melhor parar aqui, com a resposta na tela.
  if (!preparado) {
    throw new Error(`nao consegui preparar o estoque: ${resposta.status} ${resposta.body}`);
  }
}

export default function () {
  group('criacao de pedido', () => {
    const corpo = JSON.stringify({
      // precoUnitario vai como texto de proposito: o dominio converte para o
      // tipo de dinheiro dele, e numero de ponto flutuante em JSON perde
      // centavo antes mesmo de chegar la.
      itens: [{
        sku: SKU,
        descricao: 'Produto do teste de carga',
        quantidade: 1,
        precoUnitario: '29.90',
      }],
    });

    const resposta = http.post(`${BASE}/api/v1/pedidos`, corpo, {
      headers: {
        'Content-Type': 'application/json',
        'X-Cliente-Id': uuidv4(),
        // Chave nova a cada iteracao: repetir a chave testaria idempotencia,
        // que ja tem teste proprio, e aqui esconderia o custo real medindo o
        // atalho do caminho repetido.
        'Idempotency-Key': uuidv4(),
      },
      tags: { cenario: 'criacao' },
    });

    duracaoDaCriacao.add(resposta.timings.duration);

    const criou = check(resposta, {
      'aceitou o pedido': (r) => r.status === 201,
      'devolveu o id': (r) => !!(r.json() || {}).id,
    });

    if (criou) {
      pedidosCriados.add(1);
    } else {
      pedidosRejeitados.add(1);
    }
  });
}

export function handleSummary(dados) {
  const criados = dados.metrics.pedidos_criados?.values?.count ?? 0;
  const p95 = dados.metrics.http_req_duration?.values?.['p(95)'] ?? 0;
  const falha = (dados.metrics.http_req_failed?.values?.rate ?? 0) * 100;

  const resumo = [
    '',
    '[carga] ' + criados + ' pedidos criados',
    '[carga] p95 da requisicao: ' + p95.toFixed(0) + ' ms',
    '[carga] taxa de falha: ' + falha.toFixed(2) + '%',
    '[carga] medido contra docker-compose, e nao contra ambiente de producao',
    '',
  ].join('\n');

  return {
    stdout: resumo,
    'carga/resultado.json': JSON.stringify(dados, null, 2),
  };
}
