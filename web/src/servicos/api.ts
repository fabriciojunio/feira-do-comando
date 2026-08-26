/**
 * Cliente dos tres servicos.
 *
 * Tudo passa por aqui de proposito: e o unico lugar que conhece o formato do
 * Problem Details, o cabecalho de cliente e a chave de idempotencia. As telas
 * falam de tela.
 */

const BASE = import.meta.env.VITE_API_BASE ?? '';

/** Em um sistema real vem do token. Aqui e fixo, e isso esta dito no README. */
export const CLIENTE = 'cliente-demo';

/**
 * Erro no formato RFC 9457.
 *
 * O campo `codigo` e uma extensao nossa e existe justamente para a interface
 * poder ramificar sem depender do texto, que muda.
 */
export class FalhaDaApi extends Error {
  constructor(
    readonly codigo: string,
    mensagem: string,
    readonly status: number,
    readonly detalhes: Record<string, unknown> = {},
  ) {
    super(mensagem);
    this.name = 'FalhaDaApi';
  }
}

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

export const estoque = {
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

export const pedidos = {
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

export const cobrancas = {
  consultar: (pedidoId: string) => chamar<Cobranca>(`/api/v1/cobrancas/${pedidoId}`),
  resumo: () => chamar<Record<string, number>>('/api/v1/cobrancas/resumo'),
};

// ---------- Utilidades ----------

export function emReais(centavos: number): string {
  return (centavos / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

export function centavosDe(precoEmTexto: string): number {
  return Math.round(Number.parseFloat(precoEmTexto) * 100);
}
