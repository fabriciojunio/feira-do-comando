import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { montarPassos, TrilhoDaSaga } from './TrilhoDaSaga';
import type { Cobranca, Pedido } from '@/servicos/api';

/**
 * A narrativa que o cliente le.
 *
 * Vale testar porque e aqui que o estado interno da saga vira frase. Um erro
 * neste arquivo nao quebra nada no servidor e faz o cliente entender errado o
 * que aconteceu com o dinheiro dele, que e pior.
 */

function pedidoCom(campos: Partial<Pedido>): Pedido {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    status: 'RECEBIDO',
    situacao: 'Recebemos seu pedido',
    itens: [
      {
        sku: 'CAF-500',
        descricao: 'Cafe',
        quantidade: 2,
        precoUnitarioCentavos: 2490,
        precoUnitario: 'R$ 24,90',
        subtotal: 'R$ 49,80',
      },
    ],
    totalCentavos: 4980,
    total: 'R$ 49,80',
    motivoDoCancelamento: null,
    detalheDoCancelamento: null,
    criadoEm: '2026-08-26T10:00:00Z',
    atualizadoEm: '2026-08-26T10:00:00Z',
    ...campos,
  };
}

function cobrancaCom(campos: Partial<Cobranca>): Cobranca {
  return {
    pedidoId: '11111111-1111-1111-1111-111111111111',
    situacao: 'PENDENTE',
    valorCentavos: 4980,
    valor: 'R$ 49,80',
    referenciaExterna: null,
    motivoDaRecusa: null,
    criadaEm: '2026-08-26T10:00:00Z',
    encerradaEm: null,
    ...campos,
  };
}

describe('Trilho da saga', () => {
  it('mostra a separacao em andamento quando o pedido acabou de entrar', () => {
    const passos = montarPassos(pedidoCom({ status: 'RECEBIDO' }), null);

    expect(passos[0]?.situacao).toBe('concluido');
    expect(passos[1]?.situacao).toBe('agora');
    expect(passos[1]?.detalhe).toContain('Separando');
  });

  it('marca a separacao como concluida depois da reserva', () => {
    const passos = montarPassos(pedidoCom({ status: 'ESTOQUE_RESERVADO' }), null);

    expect(passos[1]?.situacao).toBe('concluido');
    expect(passos[2]?.situacao).toBe('agora');
  });

  it('mostra a transacao quando a cobranca e aprovada', () => {
    const passos = montarPassos(
      pedidoCom({ status: 'CONFIRMADO' }),
      cobrancaCom({ situacao: 'APROVADA', referenciaExterna: 'tx-abc123' }),
    );

    expect(passos[2]?.situacao).toBe('concluido');
    expect(passos[2]?.detalhe).toContain('tx-abc123');
  });

  /**
   * O caso que mais gera duvida do cliente: o dinheiro saiu e voltou. A tela
   * precisa dizer isso com todas as letras.
   */
  it('diz explicitamente quando houve estorno', () => {
    const passos = montarPassos(
      pedidoCom({ status: 'CANCELADO', motivoDoCancelamento: 'PEDIDO_DO_CLIENTE' }),
      cobrancaCom({ situacao: 'ESTORNADA', valor: 'R$ 49,80' }),
    );

    const cobranca = passos.find((p) => p.titulo === 'Cobranca');
    expect(cobranca?.situacao).toBe('falhou');
    expect(cobranca?.detalhe).toContain('estornada');
  });

  it('nao promete cobranca quando faltou estoque', () => {
    const passos = montarPassos(
      pedidoCom({
        status: 'CANCELADO',
        motivoDoCancelamento: 'SEM_ESTOQUE',
        detalheDoCancelamento: 'Sem estoque: CAF-500',
      }),
      null,
    );

    expect(passos[1]?.situacao).toBe('falhou');
    expect(passos[1]?.detalhe).toContain('CAF-500');
    expect(passos[2]?.situacao)
      .toBe('esperando');
    expect(passos[2]?.detalhe).toContain('Nao chegou a ser cobrado');
  });

  it('mostra o passo de devolucao enquanto a compensacao acontece', () => {
    const passos = montarPassos(pedidoCom({ status: 'CANCELANDO' }), null);

    expect(passos.some((p) => p.titulo.includes('Devolvendo'))).toBe(true);
  });

  it('nao mostra devolucao quando o pedido ja terminou', () => {
    const passos = montarPassos(pedidoCom({ status: 'CONFIRMADO' }), null);

    expect(passos.some((p) => p.titulo.includes('Devolvendo'))).toBe(false);
  });

  it('traduz o motivo da recusa em vez de mostrar o codigo cru', () => {
    const passos = montarPassos(
      pedidoCom({ status: 'CANCELANDO' }),
      cobrancaCom({ situacao: 'RECUSADA', motivoDaRecusa: 'SALDO_INSUFICIENTE' }),
    );

    const cobranca = passos.find((p) => p.titulo === 'Cobranca');
    expect(cobranca?.detalhe).toContain('saldo insuficiente');
    expect(cobranca?.detalhe).not.toContain('SALDO_INSUFICIENTE');
  });

  it('renderiza um item de lista por passo, com rotulo acessivel', () => {
    render(<TrilhoDaSaga pedido={pedidoCom({ status: 'CONFIRMADO' })} cobranca={null} />);

    expect(screen.getByLabelText('Andamento do pedido')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(screen.getByText('Pedido confirmado')).toBeInTheDocument();
  });

  it('o ultimo passo do pedido cancelado explica o motivo', () => {
    render(
      <TrilhoDaSaga
        pedido={pedidoCom({
          status: 'CANCELADO',
          motivoDoCancelamento: 'PAGAMENTO_RECUSADO',
          detalheDoCancelamento: 'CARTAO_INVALIDO',
        })}
        cobranca={null}
      />,
    );

    expect(screen.getByText('Pedido cancelado')).toBeInTheDocument();
    expect(screen.getByText('CARTAO_INVALIDO')).toBeInTheDocument();
  });
});
