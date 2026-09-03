import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Vitrine } from './Vitrine';
import { estoque, pedidos, FalhaDaApi, type Saldo } from '@/servicos/api';

/**
 * A vitrine.
 *
 * O que precisa de teste aqui é a fronteira entre o preço, que é local, e o
 * saldo, que vem do serviço de estoque. Um erro nessa junção deixa o cliente
 * comprar o que não existe, e o pedido só falha lá na frente, já com a saga
 * andando.
 */

vi.mock('@/servicos/api', async () => {
  const real = await vi.importActual<typeof import('@/servicos/api')>('@/servicos/api');
  return {
    ...real,
    estoque: { ...real.estoque, itens: vi.fn() },
    pedidos: { ...real.pedidos, criar: vi.fn() },
  };
});

function saldoDe(sku: string, disponivel: number, reservado = 0): Saldo {
  return {
    sku,
    descricao: sku,
    disponivel,
    reservado,
    vendido: 0,
    emMaos: disponivel + reservado,
  };
}

function montar() {
  return render(
    <MemoryRouter>
      <Vitrine />
    </MemoryRouter>,
  );
}

/** A banca do produto, para não caçar texto solto na página inteira. */
function banca(nome: string) {
  return screen.getByLabelText(`Quantidade de ${nome}`).closest('article')!;
}

describe('Vitrine', () => {
  beforeEach(() => {
    vi.mocked(estoque.itens).mockResolvedValue([]);
    vi.mocked(pedidos.criar).mockReset();
  });

  it('avisa que o estoque está vazio em vez de mostrar uma vitrine muda', async () => {
    montar();

    expect(await screen.findByText(/O estoque está vazio/)).toBeInTheDocument();
  });

  /**
   * Produto sem registro no estoque é diferente de produto esgotado. O primeiro
   * é configuração faltando, o segundo é venda perdida, e quem opera a loja
   * precisa saber qual dos dois está olhando.
   */
  it('separa produto não cadastrado de produto esgotado', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 0)]);

    montar();

    await waitFor(() => {
      expect(within(banca('Café torrado e moído') as HTMLElement).getByText('esgotado')).toBeInTheDocument();
    });
    expect(within(banca('Açúcar cristal') as HTMLElement).getByText('não cadastrado')).toBeInTheDocument();
  });

  it('destaca o saldo curto para quem está olhando a banca', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 2), saldoDe('ACU-1KG', 40)]);

    montar();

    await waitFor(() => {
      expect(within(banca('Café torrado e moído') as HTMLElement).getByText('restam 2')).toBeInTheDocument();
    });
    expect(within(banca('Açúcar cristal') as HTMLElement).getByText('40 disponíveis')).toBeInTheDocument();
  });

  /**
   * O saldo mostrado é o disponível. Unidade reservada para outro pedido não
   * está à venda, mesmo estando no galpão.
   */
  it('mostra o reservado ao lado sem somar ao que está à venda', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 4, 6)]);

    montar();

    const caf = (await waitFor(() => banca('Café torrado e moído'))) as HTMLElement;
    expect(within(caf).getByText('4 disponíveis')).toBeInTheDocument();
    expect(within(caf).getByText('6 reservados')).toBeInTheDocument();
  });

  it('não deixa levar mais do que existe, mesmo digitando direto', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 3)]);
    const usuario = userEvent.setup();

    montar();

    const campo = (await screen.findByLabelText('Quantidade de Café torrado e moído')) as HTMLInputElement;
    await usuario.clear(campo);
    await usuario.type(campo, '99');

    await waitFor(() => expect(campo.value).toBe('3'));
    expect(within(banca('Café torrado e moído') as HTMLElement).getByRole('button', { name: 'juntar' })).toBeDisabled();
  });

  it('soma a sacola com o preço do catálogo', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 10)]);
    const usuario = userEvent.setup();

    montar();

    const campo = await screen.findByLabelText('Quantidade de Café torrado e moído');
    await usuario.clear(campo);
    await usuario.type(campo, '2');

    // 2 x 24,90
    expect(await screen.findByText(/R\$\s?49,80/)).toBeInTheDocument();
    expect(screen.getByText('2 x Café torrado e moído')).toBeInTheDocument();
  });

  /**
   * O aviso de recusa forçada é o que permite demonstrar a compensação sem
   * depender de sorte. Se ele sumir, quem apresenta o sistema não consegue
   * mostrar o estorno acontecendo.
   */
  it('avisa quando o total cai nos centavos que o adquirente recusa', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('SAL-1KG', 10)]);
    const usuario = userEvent.setup();

    montar();

    const campo = await screen.findByLabelText('Quantidade de Sal refinado');

    // 6 x 2,38 = 14,28: resto 28, nenhum desfecho forçado.
    await usuario.clear(campo);
    await usuario.type(campo, '6');
    await screen.findByText(/R\$\s?14,28/);
    expect(screen.queryByText(/faz o adquirente simulado recusar/)).not.toBeInTheDocument();

    // 7 x 2,38 = 16,66: resto 66.
    await usuario.clear(campo);
    await usuario.type(campo, '7');

    expect(await screen.findByText(/faz o adquirente simulado recusar/)).toBeInTheDocument();
    expect(screen.getByText('suspeita de fraude')).toBeInTheDocument();
  });

  it('fecha o pedido com os itens da sacola e uma chave de idempotência', async () => {
    vi.mocked(estoque.itens).mockResolvedValue([saldoDe('CAF-500', 10)]);
    vi.mocked(pedidos.criar).mockResolvedValue({
      id: '11111111-1111-1111-1111-111111111111',
    } as never);
    const usuario = userEvent.setup();

    montar();

    const campo = await screen.findByLabelText('Quantidade de Café torrado e moído');
    await usuario.clear(campo);
    await usuario.type(campo, '2');
    await usuario.click(screen.getByRole('button', { name: /fechar pedido/i }));

    await waitFor(() => expect(pedidos.criar).toHaveBeenCalledTimes(1));
    const [itens, chave] = vi.mocked(pedidos.criar).mock.calls[0]!;
    expect(itens).toEqual([
      { sku: 'CAF-500', descricao: 'Café torrado e moído', quantidade: 2, precoUnitario: '24.90' },
    ]);
    expect(chave).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('diz que o serviço de estoque não respondeu em vez de mostrar prateleira vazia', async () => {
    vi.mocked(estoque.itens).mockRejectedValue(new FalhaDaApi('CONEXAO', 'Falhou', 0));

    montar();

    expect(await screen.findByText('Falhou')).toBeInTheDocument();
    expect(screen.queryByText(/O estoque está vazio/)).not.toBeInTheDocument();
  });
});
