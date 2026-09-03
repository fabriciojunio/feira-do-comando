import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DetalheDoPedido } from './DetalheDoPedido';
import { cobrancas, pedidos, FalhaDaApi, type Cobranca, type Pedido } from '@/servicos/api';

/**
 * Acompanhamento do pedido.
 *
 * O que precisa de teste é a consulta periódica: ela tem que continuar
 * enquanto a saga anda e parar sozinha no estado final. Se não parar, cada
 * pedido já terminado deixa um temporizador batendo nos três serviços para
 * sempre, e isso não aparece em nenhuma tela.
 */

vi.mock('@/servicos/api', async () => {
  const real = await vi.importActual<typeof import('@/servicos/api')>('@/servicos/api');
  return {
    ...real,
    pedidos: { ...real.pedidos, consultar: vi.fn(), cancelar: vi.fn() },
    cobrancas: { ...real.cobrancas, consultar: vi.fn() },
  };
});

const ID = '11111111-1111-1111-1111-111111111111';

function pedidoCom(campos: Partial<Pedido>): Pedido {
  return {
    id: ID,
    status: 'RECEBIDO',
    situacao: 'Recebemos seu pedido',
    itens: [
      {
        sku: 'CAF-500',
        descricao: 'Cafe torrado e moido',
        quantidade: 1,
        precoUnitarioCentavos: 2490,
        precoUnitario: 'R$ 24,90',
        subtotal: 'R$ 24,90',
      },
    ],
    totalCentavos: 2490,
    total: 'R$ 24,90',
    motivoDoCancelamento: null,
    detalheDoCancelamento: null,
    criadoEm: '2026-08-26T10:00:00Z',
    atualizadoEm: '2026-08-26T10:00:00Z',
    ...campos,
  };
}

function montar() {
  return render(
    <MemoryRouter initialEntries={[`/pedidos/${ID}`]}>
      <Routes>
        <Route path="/pedidos/:id" element={<DetalheDoPedido />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Detalhe do pedido', () => {
  beforeEach(() => {
    vi.mocked(cobrancas.consultar).mockRejectedValue(new FalhaDaApi('NAO_ENCONTRADO', 'x', 404));
    vi.mocked(pedidos.cancelar).mockReset();
  });

  afterEach(() => {
    vi.mocked(pedidos.consultar).mockReset();
  });

  /**
   * A cobrança vive em outro serviço e só nasce depois que o evento do pedido
   * chega lá. Nos primeiros instantes o 404 é o comportamento correto, e não
   * pode virar tela de erro.
   */
  it('nao trata cobranca ainda inexistente como falha', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'CONFIRMADO' }));

    montar();

    expect(await screen.findByText('Recebemos seu pedido')).toBeInTheDocument();
    expect(screen.queryByText(/Não foi possível/)).not.toBeInTheDocument();
  });

  it('para de consultar quando o pedido chega a um estado final', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'CONFIRMADO' }));

    montar();

    await screen.findByText('Recebemos seu pedido');
    await waitFor(() => expect(pedidos.consultar).toHaveBeenCalledTimes(1));

    await new Promise((r) => setTimeout(r, 1400));
    expect(pedidos.consultar).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/Atualizando a cada segundo/)).not.toBeInTheDocument();
  }, 8000);

  it('continua consultando enquanto a saga anda', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'ESTOQUE_RESERVADO' }));

    montar();

    expect(await screen.findByText(/Atualizando a cada segundo/)).toBeInTheDocument();
    await waitFor(() => expect(vi.mocked(pedidos.consultar).mock.calls.length).toBeGreaterThan(1), {
      timeout: 4000,
    });
  }, 8000);

  it('some com o botao de cancelar depois que o pedido termina', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'CANCELADO' }));

    montar();

    await screen.findByText('Recebemos seu pedido');
    expect(screen.queryByRole('button', { name: /cancelar pedido/i })).not.toBeInTheDocument();
  });

  /**
   * Cancelar um pedido já confirmado e recusado pelo servidor com 409. A tela
   * precisa mostrar o motivo que veio no Problem Details, e não um texto
   * genérico, porque é a única pista que o cliente tem.
   */
  it('mostra o motivo quando o servidor recusa o cancelamento', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'ESTOQUE_RESERVADO' }));
    vi.mocked(pedidos.cancelar).mockRejectedValue(
      new FalhaDaApi(
        'CANCELAMENTO_NAO_PERMITIDO',
        'pedido em CONFIRMADO nao pode mais ser cancelado',
        409,
      ),
    );
    const usuario = userEvent.setup();

    montar();

    await usuario.click(await screen.findByRole('button', { name: /cancelar pedido/i }));

    expect(
      await screen.findByText('pedido em CONFIRMADO nao pode mais ser cancelado'),
    ).toBeInTheDocument();
  }, 8000);

  it('mostra a situacao da cobranca quando ela ja existe', async () => {
    vi.mocked(pedidos.consultar).mockResolvedValue(pedidoCom({ status: 'CONFIRMADO' }));
    vi.mocked(cobrancas.consultar).mockResolvedValue({
      pedidoId: ID,
      situacao: 'APROVADA',
      valorCentavos: 2490,
      valor: 'R$ 24,90',
      referenciaExterna: 'tx-abc123',
      motivoDaRecusa: null,
      criadaEm: '2026-08-26T10:00:00Z',
      encerradaEm: '2026-08-26T10:00:02Z',
    } satisfies Cobranca);

    montar();

    expect(await screen.findByText(/tx-abc123/)).toBeInTheDocument();
  });

  it('oferece a volta para a vitrine quando o pedido nao existe', async () => {
    vi.mocked(pedidos.consultar).mockRejectedValue(
      new FalhaDaApi('PEDIDO_NAO_ENCONTRADO', 'Pedido nao encontrado', 404),
    );

    montar();

    expect(await screen.findByText('Pedido nao encontrado')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /voltar para a vitrine/i })).toBeInTheDocument();
  });
});
