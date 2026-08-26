import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { centavosDe, emReais, estoque, FalhaDaApi, pedidos } from './api';
import { CATALOGO, desfechoForcado, produtoDe } from './catalogo';

/**
 * O cliente da API.
 *
 * O que importa testar aqui e o tratamento do erro: e ele que decide se a tela
 * mostra "sem estoque" ou "algo deu errado". O caminho feliz o backend ja
 * cobre; o formato do Problem Details, nao.
 */

const originalFetch = globalThis.fetch;

function responder(corpo: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(corpo), {
      status,
      headers: { 'content-type': 'application/problem+json' },
    }),
  );
}

describe('Cliente da API', () => {
  beforeEach(() => {
    globalThis.fetch = vi.fn();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('manda o cabecalho de cliente em toda chamada', async () => {
    vi.mocked(globalThis.fetch).mockReturnValue(responder([]));

    await estoque.itens();

    const [, opcoes] = vi.mocked(globalThis.fetch).mock.calls[0]!;
    const cabecalhos = (opcoes as RequestInit).headers as Record<string, string>;
    expect(cabecalhos['X-Cliente-Id']).toBeTruthy();
  });

  it('manda a chave de idempotencia ao criar pedido', async () => {
    vi.mocked(globalThis.fetch).mockReturnValue(responder({ id: 'abc' }));

    await pedidos.criar(
      [{ sku: 'CAF-500', descricao: 'Cafe', quantidade: 1, precoUnitario: '24.90' }],
      'chave-123',
    );

    const [, opcoes] = vi.mocked(globalThis.fetch).mock.calls[0]!;
    const cabecalhos = (opcoes as RequestInit).headers as Record<string, string>;
    expect(cabecalhos['Idempotency-Key']).toBe('chave-123');
  });

  it('transforma o Problem Details em FalhaDaApi com o codigo preservado', async () => {
    vi.mocked(globalThis.fetch).mockReturnValue(
      responder(
        {
          type: 'https://feira.dev/erros/cancelamento-nao-permitido',
          title: 'Pedido nao pode mais ser cancelado',
          detail: 'pedido em CONFIRMADO nao pode mais ser cancelado',
          codigo: 'CANCELAMENTO_NAO_PERMITIDO',
          statusDoPedido: 'CONFIRMADO',
        },
        409,
      ),
    );

    await expect(pedidos.cancelar('abc')).rejects.toSatisfy((erro: unknown) => {
      expect(erro).toBeInstanceOf(FalhaDaApi);
      const falha = erro as FalhaDaApi;
      expect(falha.codigo).toBe('CANCELAMENTO_NAO_PERMITIDO');
      expect(falha.status).toBe(409);
      expect(falha.message).toContain('CONFIRMADO');
      return true;
    });
  });

  it('usa um codigo generico quando a resposta nao traz o campo', async () => {
    vi.mocked(globalThis.fetch).mockReturnValue(responder({ title: 'Erro' }, 500));

    await expect(estoque.itens()).rejects.toMatchObject({ codigo: 'ERRO', status: 500 });
  });
});

describe('Formatacao de valores', () => {
  it('converte centavos em real brasileiro', () => {
    expect(emReais(2490).replace(/ /g, ' ')).toBe('R$ 24,90');
    expect(emReais(0).replace(/ /g, ' ')).toBe('R$ 0,00');
    expect(emReais(123456).replace(/ /g, ' ')).toBe('R$ 1.234,56');
  });

  it('converte texto de preco em centavos sem perder o arredondamento', () => {
    expect(centavosDe('24.90')).toBe(2490);
    expect(centavosDe('6.20')).toBe(620);
    expect(centavosDe('0.01')).toBe(1);
  });
});

describe('Catalogo', () => {
  it('acha o produto pelo SKU', () => {
    expect(produtoDe('CAF-500')?.nome).toContain('Cafe');
    expect(produtoDe('NAO-EXISTE')).toBeUndefined();
  });

  /**
   * Os centavos que forcam recusa sao o que permite demonstrar a compensacao
   * sem depender de sorte. Se a tabela mudar sem o adquirente mudar junto, a
   * demonstracao para de funcionar e ninguem percebe.
   */
  it('reconhece os totais que forcam recusa no adquirente simulado', () => {
    expect(desfechoForcado(1013)).toBe('saldo insuficiente');
    expect(desfechoForcado(9966)).toBe('suspeita de fraude');
    expect(desfechoForcado(2499)).toBe('falha temporaria');
    expect(desfechoForcado(2490)).toBeUndefined();
  });

  /**
   * O recurso so existe se algum total possivel cair nos restos. Com todos os
   * precos terminando em zero, nenhum cai, e a tela nunca mostra o aviso. Foi
   * exatamente o que aconteceu ate 26/08: o codigo estava certo e o catalogo
   * tornava o caminho inalcancavel.
   */
  it('alcancaOsTresDesfechosForcados com quantidades pequenas', () => {
    const preco = (sku: string) => centavosDe(produtoDe(sku)!.preco);

    expect(desfechoForcado(preco('MAC-500'))).toBe('falha temporaria');
    expect(desfechoForcado(preco('SAL-1KG') * 7)).toBe('suspeita de fraude');
    expect(desfechoForcado(preco('MAC-500') + preco('SAL-1KG') * 3)).toBe('saldo insuficiente');
  });

  it('mantem pelo menos dois precos com centavo quebrado', () => {
    const quebrados = CATALOGO.filter((p) => centavosDe(p.preco) % 10 !== 0);
    expect(quebrados.length).toBeGreaterThanOrEqual(2);
  });
});
