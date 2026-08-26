import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MeusPedidos } from './MeusPedidos';
import { legivel, seloDe } from './DetalheDoPedido';
import { pedidos, FalhaDaApi, type Pedido } from '@/servicos/api';

vi.mock('@/servicos/api', async () => {
  const real = await vi.importActual<typeof import('@/servicos/api')>('@/servicos/api');
  return { ...real, pedidos: { ...real.pedidos, listar: vi.fn() } };
});

function pedidoCom(campos: Partial<Pedido>): Pedido {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    status: 'CONFIRMADO',
    situacao: 'Pedido confirmado',
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
    atualizadoEm: '2026-08-26T10:00:03Z',
    ...campos,
  };
}

function montar() {
  return render(
    <MemoryRouter>
      <MeusPedidos />
    </MemoryRouter>,
  );
}

describe('Meus pedidos', () => {
  beforeEach(() => {
    vi.mocked(pedidos.listar).mockReset();
  });

  it('manda quem nao tem pedido de volta para a vitrine', async () => {
    vi.mocked(pedidos.listar).mockResolvedValue({ conteudo: [] } as never);

    montar();

    expect(await screen.findByText(/Nenhum pedido ainda/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /comece pela vitrine/i })).toBeInTheDocument();
  });

  it('resume os itens sem obrigar a abrir o pedido', async () => {
    vi.mocked(pedidos.listar).mockResolvedValue({ conteudo: [pedidoCom({})] } as never);

    montar();

    expect(await screen.findByText('2x CAF-500')).toBeInTheDocument();
    expect(screen.getByText('R$ 49,80')).toBeInTheDocument();
    expect(screen.getByText('confirmado')).toBeInTheDocument();
  });

  /**
   * Numa lista, o motivo do cancelamento e a unica coisa que diferencia
   * "acabou o estoque" de "seu cartao foi recusado". Sem ele o cliente abre um
   * a um para descobrir.
   */
  it('mostra o motivo do cancelamento na propria linha', async () => {
    vi.mocked(pedidos.listar).mockResolvedValue({
      conteudo: [pedidoCom({ status: 'CANCELADO', motivoDoCancelamento: 'PAGAMENTO_RECUSADO' })],
    } as never);

    montar();

    expect(await screen.findByText('pagamento recusado')).toBeInTheDocument();
  });

  it('diz que a listagem falhou em vez de fingir lista vazia', async () => {
    vi.mocked(pedidos.listar).mockRejectedValue(new FalhaDaApi('ERRO', 'Servico fora do ar', 503));

    montar();

    expect(await screen.findByText('Servico fora do ar')).toBeInTheDocument();
    expect(screen.queryByText(/Nenhum pedido ainda/)).not.toBeInTheDocument();
  });
});

/**
 * As duas funcoes vivem no detalhe e sao reusadas aqui. Se a cor do selo
 * divergir entre a lista e o detalhe, o mesmo pedido aparece verde numa tela e
 * laranja na outra.
 */
describe('Selo e rotulo de status', () => {
  it('usa verde so para o que terminou bem e tijolo so para o que falhou', () => {
    expect(seloDe('CONFIRMADO')).toBe('ok');
    expect(seloDe('CANCELADO')).toBe('ruim');
    expect(seloDe('RECEBIDO')).toBe('andamento');
    expect(seloDe('ESTOQUE_RESERVADO')).toBe('andamento');
    expect(seloDe('CANCELANDO')).toBe('andamento');
  });

  it('mostra o status em portugues corrido, sem o codigo do banco', () => {
    expect(legivel('ESTOQUE_RESERVADO')).toBe('estoque reservado');
    expect(legivel('CANCELANDO')).toBe('cancelando');
  });
});
