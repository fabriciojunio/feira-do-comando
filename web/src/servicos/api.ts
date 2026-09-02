/**
 * Cliente dos tres servicos.
 *
 * Tudo passa por aqui de proposito: e o unico lugar que conhece o formato do
 * Problem Details, o cabecalho de cliente e a chave de idempotencia. As telas
 * falam de tela.
 */

import { estoqueSimulado, pedidosSimulados, cobrancasSimuladas } from './demonstracao';
import { FalhaDaApi } from './falha';

// Reexportado para as telas continuarem importando tudo de um lugar so.
export { FalhaDaApi };

const BASE = import.meta.env.VITE_API_BASE ?? '';

/**
 * Liga a saga simulada no navegador, para a demonstracao publicada.
 *
 * O sistema de verdade sao quatro servicos Java, um Kafka e tres bancos, e isso
 * nao cabe em camada gratuita. Sem este modo, a tela publicada carregaria e
 * quebraria no primeiro clique, o que e pior que nao ter demonstracao.
 *
 * Mesma ideia dos adaptadores do back-end: a tela nao sabe qual dos dois esta
 * atendendo, e o contrato e o mesmo.
 */
export const MODO_DEMONSTRACAO = import.meta.env.VITE_DEMONSTRACAO === 'true';

/** Em um sistema real vem do token. Aqui e fixo, e isso esta dito no README. */
export const CLIENTE = 'cliente-demo';


type Opcoes = {
  metodo?: 'GET' | 'POST' | 'DELETE';
  corpo?: unknown;
  cabecalhos?: Record<string, string>;
};

async function chamar<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  const resposta = await fetch(`${BASE}${caminho}`, {
    method: opcoes.metodo ?? 'GET',
    headers: {
      'Content-Type': 'application/json',
      'X-Cliente-Id': CLIENTE,
      ...opcoes.cabecalhos,
    },
    body: opcoes.corpo === undefined ? undefined : JSON.stringify(opcoes.corpo),
  });

  if (resposta.status === 204) return undefined as T;

  const tipo = resposta.headers.get('content-type') ?? '';
  const corpo = tipo.includes('json') ? await resposta.json() : await resposta.text();

  if (!resposta.ok) {
    const problema = corpo as {
      codigo?: string;
      detail?: string;
      title?: string;
      [chave: string]: unknown;
    };
    throw new FalhaDaApi(
      problema?.codigo ?? 'ERRO',
      problema?.detail ?? problema?.title ?? 'Nao foi possivel completar a operacao',
      resposta.status,
      problema ?? {},
    );
  }

  return corpo as T;
}

// ---------- Estoque ----------

export type Saldo = {
  sku: string;
  descricao: string;
  disponivel: number;
  reservado: number;
  vendido: number;
  emMaos: number;
};

const estoqueHttp = {
  itens: () => chamar<Saldo[]>('/api/v1/estoque/itens'),
  reposicao: (limite = 5) => chamar<Saldo[]>(`/api/v1/estoque/reposicao?limite=${limite}`),
  resumo: () => chamar<Record<string, number>>('/api/v1/estoque/reservas'),
  cadastrar: (sku: string, descricao: string, quantidade: number) =>
    chamar<Saldo>('/api/v1/estoque/itens', {
      metodo: 'POST',
      corpo: { sku, descricao, quantidade },
    }),
};

// ---------- Pedidos ----------

export type ItemDoPedido = {
  sku: string;
  descricao: string;
  quantidade: number;
  precoUnitarioCentavos: number;
  precoUnitario: string;
  subtotal: string;
};

export type Pedido = {
  id: string;
  status: 'RECEBIDO' | 'ESTOQUE_RESERVADO' | 'CANCELANDO' | 'CONFIRMADO' | 'CANCELADO';
  situacao: string;
  itens: ItemDoPedido[];
  totalCentavos: number;
  total: string;
  motivoDoCancelamento: string | null;
  detalheDoCancelamento: string | null;
  criadoEm: string;
  atualizadoEm: string;
};

export type Pagina<T> = {
  conteudo: T[];
  pagina: number;
  tamanho: number;
  total: number;
  paginas: number;
};

export type LinhaDoCarrinho = {
  sku: string;
  descricao: string;
  quantidade: number;
  precoUnitario: string;
};

const pedidosHttp = {
  criar: (itens: LinhaDoCarrinho[], chaveDeIdempotencia: string) =>
    chamar<Pedido>('/api/v1/pedidos', {
      metodo: 'POST',
      // Rede cai, botao e clicado duas vezes, aplicativo tenta de novo. Sem a
      // chave, cada tentativa vira um pedido e o cliente e cobrado tres vezes.
      cabecalhos: { 'Idempotency-Key': chaveDeIdempotencia },
      corpo: { itens },
    }),

  consultar: (id: string) => chamar<Pedido>(`/api/v1/pedidos/${id}`),

  listar: (tamanho = 20) => chamar<Pagina<Pedido>>(`/api/v1/pedidos?tamanho=${tamanho}`),

  cancelar: (id: string) => chamar<Pedido>(`/api/v1/pedidos/${id}`, { metodo: 'DELETE' }),
};

// ---------- Cobrancas ----------

export type Cobranca = {
  pedidoId: string;
  situacao: 'PENDENTE' | 'APROVADA' | 'RECUSADA' | 'ESTORNADA';
  valorCentavos: number;
  valor: string;
  referenciaExterna: string | null;
  motivoDaRecusa: string | null;
  criadaEm: string;
  encerradaEm: string | null;
};

const cobrancasHttp = {
  consultar: (pedidoId: string) => chamar<Cobranca>(`/api/v1/cobrancas/${pedidoId}`),
  resumo: () => chamar<Record<string, number>>('/api/v1/cobrancas/resumo'),
};

// A escolha acontece uma vez, aqui. As telas importam estoque, pedidos e
// cobrancas sem saber de onde vem a resposta.
export const estoque = MODO_DEMONSTRACAO ? estoqueSimulado : estoqueHttp;
export const pedidos = MODO_DEMONSTRACAO ? pedidosSimulados : pedidosHttp;
export const cobrancas = MODO_DEMONSTRACAO ? cobrancasSimuladas : cobrancasHttp;

// ---------- Utilidades ----------

export function emReais(centavos: number): string {
  return (centavos / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

export function centavosDe(precoEmTexto: string): number {
  return Math.round(Number.parseFloat(precoEmTexto) * 100);
}
