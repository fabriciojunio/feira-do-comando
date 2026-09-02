/**
 * A saga rodando no navegador, para a demonstração publicada.
 *
 * <h2>Por que isto existe</h2>
 * O sistema de verdade são quatro serviços Java, um Kafka e três bancos. Isso
 * não cabe em camada gratuita, e publicar a tela apontando para uma API que não
 * existe daria uma vitrine que carrega e quebra no primeiro clique. Vitrine
 * quebrada é pior que vitrine nenhuma.
 *
 * <h2>O que ele preserva</h2>
 * A máquina de estados, que é o que o projeto existe para mostrar. As mesmas
 * transições do agregado de pedido, a mesma corrida entre aprovação do
 * pagamento e cancelamento, a mesma reserva de estoque que volta quando o
 * cartão recusa. Quem clica aqui vê a saga andar de verdade, só que dentro da
 * aba do navegador.
 *
 * <h2>O que ele não é</h2>
 * Não é o sistema. Não há outbox, não há Kafka, não há transação, não há
 * idempotência garantida por banco. Isto simula o comportamento observável, e
 * a tela diz isso em cima, para ninguém confundir demonstração com produção.
 * Quem quiser o sistema roda `docker compose up -d`, que sobe tudo.
 */

import type { Cobranca, LinhaDoCarrinho, Pagina, Pedido, Saldo } from './api';
import { FalhaDaApi } from './falha';
import { CATALOGO } from './catalogo';

/**
 * Quanto cada etapa demora.
 *
 * Instantâneo esconderia justamente o que a tela tem para mostrar: a saga é
 * uma sequência de passos assíncronos, e ver o pedido parado em
 * ESTOQUE_RESERVADO esperando o pagamento é o ponto.
 */
const RESERVA_MS = 900;
const PAGAMENTO_MS = 2200;
const ESTORNO_MS = 1400;

/** SKU que o adquirente sempre recusa, para a demonstração de compensação. */
const SKU_QUE_RECUSA = 'OLE-900';

const pedidosNaMemoria = new Map<string, Pedido>();
const cobrancasNaMemoria = new Map<string, Cobranca>();

const saldos = new Map<string, Saldo>(
  CATALOGO.map((produto) => [
    produto.sku,
    {
      sku: produto.sku,
      descricao: produto.nome,
      disponivel: 40,
      reservado: 0,
      vendido: 0,
      emMaos: 40,
    },
  ]),
);

/**
 * A chave de idempotência já usada, apontando para o pedido que ela criou.
 *
 * Aqui é um mapa; no sistema é uma restrição de unicidade no banco, que é o
 * que resolve a corrida de verdade. O comportamento visível é o mesmo:
 * repetir a chave devolve o pedido de antes em vez de criar outro.
 */
const chavesUsadas = new Map<string, string>();

function agora(): string {
  return new Date().toISOString();
}

function emReaisTexto(centavos: number): string {
  return (centavos / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function centavos(precoEmTexto: string): number {
  return Math.round(Number.parseFloat(precoEmTexto) * 100);
}

function depois(ms: number, acao: () => void): void {
  setTimeout(acao, ms);
}

/**
 * Move o pedido pela saga, do jeito que o agregado faz no serviço.
 *
 * A ordem importa e é a mesma: reserva primeiro, cobrança depois. Se a cobrança
 * recusa, a reserva volta, porque saga não tem rollback distribuído e sim
 * compensação.
 */
function tocarASaga(pedido: Pedido): void {
  depois(RESERVA_MS, () => {
    const guardado = pedidosNaMemoria.get(pedido.id);
    if (!guardado || guardado.status === 'CANCELADO') return;

    for (const item of guardado.itens) {
      const saldo = saldos.get(item.sku);
      if (saldo) {
        saldo.disponivel -= item.quantidade;
        saldo.reservado += item.quantidade;
      }
    }

    guardado.status = 'ESTOQUE_RESERVADO';
    guardado.situacao = 'Estoque reservado, aguardando o pagamento';
    guardado.atualizadoEm = agora();

    depois(PAGAMENTO_MS, () => cobrar(guardado));
  });
}

function cobrar(pedido: Pedido): void {
  const guardado = pedidosNaMemoria.get(pedido.id);
  if (!guardado) return;

  const recusa = guardado.itens.some((item) => item.sku === SKU_QUE_RECUSA);
  const cobranca = cobrancasNaMemoria.get(guardado.id);

  if (recusa) {
    if (cobranca) {
      cobranca.situacao = 'RECUSADA';
      cobranca.motivoDaRecusa = 'saldo insuficiente no cartão';
      cobranca.encerradaEm = agora();
    }
    devolverAReserva(guardado);
    guardado.status = 'CANCELADO';
    guardado.situacao = 'Pagamento recusado, reserva devolvida';
    guardado.motivoDoCancelamento = 'PAGAMENTO_RECUSADO';
    guardado.detalheDoCancelamento = 'saldo insuficiente no cartão';
    guardado.atualizadoEm = agora();
    return;
  }

  // A corrida que o projeto existe para tratar: se o cancelamento já começou
  // quando a aprovação chega, o dinheiro é estornado em vez de o pedido virar
  // confirmado.
  if (guardado.status === 'CANCELANDO') {
    if (cobranca) {
      cobranca.situacao = 'APROVADA';
      cobranca.encerradaEm = agora();
    }
    depois(ESTORNO_MS, () => {
      const atual = cobrancasNaMemoria.get(guardado.id);
      if (atual) atual.situacao = 'ESTORNADA';
      devolverAReserva(guardado);
      guardado.status = 'CANCELADO';
      guardado.situacao = 'Cancelado a pedido do cliente, com o pagamento estornado';
      guardado.atualizadoEm = agora();
    });
    return;
  }

  if (cobranca) {
    cobranca.situacao = 'APROVADA';
    cobranca.referenciaExterna = `sim-${guardado.id.slice(0, 8)}`;
    cobranca.encerradaEm = agora();
  }

  for (const item of guardado.itens) {
    const saldo = saldos.get(item.sku);
    if (saldo) {
      saldo.reservado -= item.quantidade;
      saldo.vendido += item.quantidade;
      saldo.emMaos -= item.quantidade;
    }
  }

  guardado.status = 'CONFIRMADO';
  guardado.situacao = 'Pagamento aprovado, pedido confirmado';
  guardado.atualizadoEm = agora();
}

function devolverAReserva(pedido: Pedido): void {
  for (const item of pedido.itens) {
    const saldo = saldos.get(item.sku);
    if (saldo) {
      saldo.reservado = Math.max(0, saldo.reservado - item.quantidade);
      saldo.disponivel += item.quantidade;
    }
  }
}

export const estoqueSimulado = {
  itens: async (): Promise<Saldo[]> => [...saldos.values()],

  reposicao: async (limite = 5): Promise<Saldo[]> =>
    [...saldos.values()].sort((a, b) => a.disponivel - b.disponivel).slice(0, limite),

  resumo: async (): Promise<Record<string, number>> => {
    const reservado = [...saldos.values()].reduce((soma, s) => soma + s.reservado, 0);
    const vendido = [...saldos.values()].reduce((soma, s) => soma + s.vendido, 0);
    return { RESERVADO: reservado, CONFIRMADA: vendido };
  },

  cadastrar: async (sku: string, descricao: string, quantidade: number): Promise<Saldo> => {
    const existente = saldos.get(sku);
    if (existente) {
      existente.disponivel += quantidade;
      existente.emMaos += quantidade;
      return existente;
    }
    const novo: Saldo = {
      sku,
      descricao,
      disponivel: quantidade,
      reservado: 0,
      vendido: 0,
      emMaos: quantidade,
    };
    saldos.set(sku, novo);
    return novo;
  },
};

export const pedidosSimulados = {
  criar: async (itens: LinhaDoCarrinho[], chaveDeIdempotencia: string): Promise<Pedido> => {
    const jaCriado = chavesUsadas.get(chaveDeIdempotencia);
    if (jaCriado) {
      const anterior = pedidosNaMemoria.get(jaCriado);
      if (anterior) return anterior;
    }

    const semSaldo = itens.find((linha) => {
      const saldo = saldos.get(linha.sku);
      return !saldo || saldo.disponivel < linha.quantidade;
    });
    if (semSaldo) {
      throw new FalhaDaApi(
        'ESTOQUE_INSUFICIENTE',
        `Não há saldo para ${semSaldo.sku}`,
        409,
      );
    }

    const id = crypto.randomUUID();
    const itensDoPedido = itens.map((linha) => {
      const unitario = centavos(linha.precoUnitario);
      return {
        sku: linha.sku,
        descricao: linha.descricao,
        quantidade: linha.quantidade,
        precoUnitarioCentavos: unitario,
        precoUnitario: emReaisTexto(unitario),
        subtotal: emReaisTexto(unitario * linha.quantidade),
      };
    });
    const total = itensDoPedido.reduce(
      (soma, item) => soma + item.precoUnitarioCentavos * item.quantidade,
      0,
    );

    const pedido: Pedido = {
      id,
      status: 'RECEBIDO',
      situacao: 'Pedido recebido, reservando estoque',
      itens: itensDoPedido,
      totalCentavos: total,
      total: emReaisTexto(total),
      motivoDoCancelamento: null,
      detalheDoCancelamento: null,
      criadoEm: agora(),
      atualizadoEm: agora(),
    };

    pedidosNaMemoria.set(id, pedido);
    chavesUsadas.set(chaveDeIdempotencia, id);
    cobrancasNaMemoria.set(id, {
      pedidoId: id,
      situacao: 'PENDENTE',
      valorCentavos: total,
      valor: emReaisTexto(total),
      referenciaExterna: null,
      motivoDaRecusa: null,
      criadaEm: agora(),
      encerradaEm: null,
    });

    tocarASaga(pedido);
    return pedido;
  },

  consultar: async (id: string): Promise<Pedido> => {
    const pedido = pedidosNaMemoria.get(id);
    if (!pedido) {
      throw new FalhaDaApi('PEDIDO_NAO_ENCONTRADO', 'Pedido não encontrado', 404);
    }
    return pedido;
  },

  listar: async (tamanho = 20): Promise<Pagina<Pedido>> => {
    const todos = [...pedidosNaMemoria.values()].sort((a, b) =>
      b.criadoEm.localeCompare(a.criadoEm),
    );
    const conteudo = todos.slice(0, tamanho);
    return {
      conteudo,
      pagina: 0,
      tamanho,
      total: todos.length,
      paginas: Math.max(1, Math.ceil(todos.length / tamanho)),
    };
  },

  cancelar: async (id: string): Promise<Pedido> => {
    const pedido = pedidosNaMemoria.get(id);
    if (!pedido) {
      throw new FalhaDaApi('PEDIDO_NAO_ENCONTRADO', 'Pedido não encontrado', 404);
    }
    if (pedido.status === 'CONFIRMADO') {
      throw new FalhaDaApi(
        'PEDIDO_JA_CONFIRMADO',
        'Pedido já confirmado não pode ser cancelado por aqui',
        409,
      );
    }
    if (pedido.status === 'CANCELADO') return pedido;

    // Não vai direto para CANCELADO: a cobrança pode estar em andamento, e é
    // essa janela que gera o estorno. É a corrida que o projeto trata.
    pedido.status = 'CANCELANDO';
    pedido.situacao = 'Cancelamento em andamento';
    pedido.motivoDoCancelamento = 'PEDIDO_DO_CLIENTE';
    pedido.atualizadoEm = agora();

    const cobranca = cobrancasNaMemoria.get(id);
    if (!cobranca || cobranca.situacao === 'PENDENTE') {
      depois(RESERVA_MS, () => {
        const atual = pedidosNaMemoria.get(id);
        if (!atual || atual.status !== 'CANCELANDO') return;
        devolverAReserva(atual);
        atual.status = 'CANCELADO';
        atual.situacao = 'Cancelado a pedido do cliente';
        atual.atualizadoEm = agora();
      });
    }

    return pedido;
  },
};

export const cobrancasSimuladas = {
  consultar: async (pedidoId: string): Promise<Cobranca> => {
    const cobranca = cobrancasNaMemoria.get(pedidoId);
    if (!cobranca) {
      throw new FalhaDaApi('COBRANCA_NAO_ENCONTRADA', 'Cobrança não encontrada', 404);
    }
    return cobranca;
  },

  resumo: async (): Promise<Record<string, number>> => {
    const contagem: Record<string, number> = {};
    for (const cobranca of cobrancasNaMemoria.values()) {
      contagem[cobranca.situacao] = (contagem[cobranca.situacao] ?? 0) + 1;
    }
    return contagem;
  },
};

/** O SKU que recusa, para a tela poder avisar antes de o cliente clicar. */
export const SKU_DE_RECUSA = SKU_QUE_RECUSA;
