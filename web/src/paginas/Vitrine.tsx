import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  centavosDe,
  emReais,
  estoque,
  pedidos,
  type LinhaDoCarrinho,
  type Saldo,
  FalhaDaApi,
} from '@/servicos/api';
import { CATALOGO, desfechoForcado } from '@/servicos/catalogo';

/**
 * A vitrine.
 *
 * Combina o preco, que e local, com o saldo, que vem do servico de estoque.
 * O saldo mostrado e o disponivel, e nao o que existe no galpao: unidade
 * reservada para outro pedido nao esta a venda, e mostrar o total daria a
 * impressao errada de que ainda da para comprar.
 */
export function Vitrine() {
  const navegar = useNavigate();
  const [saldos, setSaldos] = useState<Record<string, Saldo>>({});
  const [carrinho, setCarrinho] = useState<Record<string, number>>({});
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    void carregarSaldos();
  }, []);

  async function carregarSaldos() {
    try {
      const itens = await estoque.itens();
      setSaldos(Object.fromEntries(itens.map((i) => [i.sku, i])));
      setErro(null);
    } catch (e) {
      setErro(
        e instanceof FalhaDaApi
          ? e.message
          : 'Nao foi possivel falar com o servico de estoque. Ele esta no ar?',
      );
    } finally {
      setCarregando(false);
    }
  }

  const linhas = useMemo<LinhaDoCarrinho[]>(
    () =>
      Object.entries(carrinho)
        .filter(([, quantidade]) => quantidade > 0)
        .map(([sku, quantidade]) => {
          const produto = CATALOGO.find((p) => p.sku === sku)!;
          return {
            sku,
            descricao: produto.nome,
            quantidade,
            precoUnitario: produto.preco,
          };
        }),
    [carrinho],
  );

  const totalEmCentavos = useMemo(
    () => linhas.reduce((soma, l) => soma + centavosDe(l.precoUnitario) * l.quantidade, 0),
    [linhas],
  );

  const forcado = desfechoForcado(totalEmCentavos);

  function ajustar(sku: string, quantidade: number) {
    const disponivel = saldos[sku]?.disponivel ?? 0;
    const valor = Math.max(0, Math.min(quantidade, disponivel));
    setCarrinho((atual) => ({ ...atual, [sku]: valor }));
  }

  async function fecharPedido() {
    if (linhas.length === 0) return;
    setEnviando(true);
    setErro(null);
    try {
      // A chave e gerada uma vez por tentativa de compra. Se a rede cair e o
      // usuario clicar de novo, o servidor devolve o mesmo pedido em vez de
      // criar outro.
      const chave = crypto.randomUUID();
      const pedido = await pedidos.criar(linhas, chave);
      navegar(`/pedidos/${pedido.id}`);
    } catch (e) {
      setErro(e instanceof FalhaDaApi ? e.message : 'Nao foi possivel fechar o pedido.');
      setEnviando(false);
    }
  }

  return (
    <>
      <section className="abertura">
        <div className="envolucro">
          <h1>O que tem hoje na feira</h1>
          <p>
            O saldo abaixo vem do servico de estoque em tempo real. Ao fechar o pedido, voce
            acompanha passo a passo o que os tres servicos combinam entre si.
          </p>
        </div>
      </section>

      <section className="secao">
        <div className="envolucro">
          {erro && <div className="aviso erro">{erro}</div>}
          {carregando && <p className="carregando">Carregando as bancas...</p>}

          {!carregando && Object.keys(saldos).length === 0 && !erro && (
            <div className="aviso">
              O estoque esta vazio. Abra a aba <strong>Operacao</strong> e cadastre saldo para
              comecar.
            </div>
          )}

          <div className="grade-produtos">
            {CATALOGO.map((produto) => {
              const saldo = saldos[produto.sku];
              const disponivel = saldo?.disponivel ?? 0;
              const naVitrine = saldo !== undefined;
              const quantidade = carrinho[produto.sku] ?? 0;

              return (
                <article key={produto.sku} className="produto">
                  <span className="sku">{produto.sku}</span>
                  <span className="nome">{produto.nome}</span>
                  <span className="apagado">{produto.unidade}</span>
                  <span className="preco">{emReais(centavosDe(produto.preco))}</span>

                  <div>
                    {!naVitrine && <span className="selo esgotado">nao cadastrado</span>}
                    {naVitrine && disponivel === 0 && <span className="selo ruim">esgotado</span>}
                    {naVitrine && disponivel > 0 && disponivel <= 3 && (
                      <span className="selo andamento">restam {disponivel}</span>
                    )}
                    {naVitrine && disponivel > 3 && (
                      <span className="selo ok">{disponivel} disponiveis</span>
                    )}
                    {saldo !== undefined && saldo.reservado > 0 && (
                      <span className="apagado" style={{ marginLeft: 8 }}>
                        {saldo.reservado} reservados
                      </span>
                    )}
                  </div>

                  <div className="acoes">
                    <input
                      className="quantidade"
                      type="number"
                      min={0}
                      max={disponivel}
                      value={quantidade}
                      disabled={disponivel === 0}
                      aria-label={`Quantidade de ${produto.nome}`}
                      onChange={(e) => ajustar(produto.sku, Number(e.target.value))}
                    />
                    <button
                      className="botao secundario pequeno"
                      disabled={disponivel === 0 || quantidade >= disponivel}
                      onClick={() => ajustar(produto.sku, quantidade + 1)}
                    >
                      juntar
                    </button>
                  </div>
                </article>
              );
            })}
          </div>
        </div>
      </section>

      {linhas.length > 0 && (
        <section className="secao" style={{ paddingTop: 0 }}>
          <div className="envolucro">
            <div className="caixote">
              <div className="espalha">
                <div>
                  <h2 className="titulo-secao" style={{ marginBottom: 8 }}>
                    Sua sacola
                  </h2>
                  <div className="fraco">
                    {linhas.map((l) => (
                      <div key={l.sku}>
                        {l.quantidade} x {l.descricao}
                      </div>
                    ))}
                  </div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div className="apagado">total</div>
                  <div className="numero-grande">{emReais(totalEmCentavos)}</div>
                </div>
              </div>

              {forcado && (
                <div className="aviso" style={{ marginTop: 16 }}>
                  Este total termina em <strong>{totalEmCentavos % 100}</strong> centavos, o que
                  faz o adquirente simulado recusar por <strong>{forcado}</strong>. Use isso para
                  ver a compensacao acontecer.
                </div>
              )}

              <button
                className="botao largo"
                style={{ marginTop: 16 }}
                onClick={() => void fecharPedido()}
                disabled={enviando}
              >
                {enviando ? 'Fechando...' : 'Fechar pedido'}
              </button>
            </div>
          </div>
        </section>
      )}
    </>
  );
}
