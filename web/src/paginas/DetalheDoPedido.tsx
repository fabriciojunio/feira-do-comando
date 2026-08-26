import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { TrilhoDaSaga } from '@/componentes/TrilhoDaSaga';
import { cobrancas, pedidos, FalhaDaApi, type Cobranca, type Pedido } from '@/servicos/api';

const ESTADOS_FINAIS = ['CONFIRMADO', 'CANCELADO'];

/**
 * Acompanhamento de um pedido.
 *
 * <p>A tela consulta a cada segundo enquanto a saga estiver andando e para
 * sozinha quando o pedido chega a um estado final. Consulta periodica nao e o
 * jeito mais elegante, e e o certo aqui: server-sent events exigiriam manter
 * conexao aberta por pedido, e a saga inteira dura poucos segundos.
 *
 * <p>A cobranca vem de outro servico e pode ainda nao existir. Isso nao e erro:
 * o registro so aparece depois que o evento do pedido chega la, e a tela
 * precisa lidar com essa janela em vez de mostrar falha.
 */
export function DetalheDoPedido() {
  const { id = '' } = useParams();
  const [pedido, setPedido] = useState<Pedido | null>(null);
  const [cobranca, setCobranca] = useState<Cobranca | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [cancelando, setCancelando] = useState(false);
  const relogio = useRef<number | null>(null);

  const buscar = useCallback(async () => {
    try {
      const atual = await pedidos.consultar(id);
      setPedido(atual);
      setErro(null);

      try {
        setCobranca(await cobrancas.consultar(id));
      } catch {
        // Ainda nao registrada. Esperado nos primeiros instantes.
        setCobranca(null);
      }

      return atual;
    } catch (e) {
      setErro(e instanceof FalhaDaApi ? e.message : 'Nao foi possivel carregar o pedido.');
      return null;
    }
  }, [id]);

  useEffect(() => {
    let ativo = true;

    async function ciclo() {
      const atual = await buscar();
      if (!ativo) return;
      if (atual && ESTADOS_FINAIS.includes(atual.status)) {
        return;
      }
      relogio.current = window.setTimeout(ciclo, 1000);
    }

    void ciclo();

    return () => {
      ativo = false;
      if (relogio.current) window.clearTimeout(relogio.current);
    };
  }, [buscar]);

  async function cancelar() {
    setCancelando(true);
    try {
      setPedido(await pedidos.cancelar(id));
      setErro(null);
    } catch (e) {
      setErro(e instanceof FalhaDaApi ? e.message : 'Nao foi possivel cancelar.');
    } finally {
      setCancelando(false);
    }
  }

  if (erro && !pedido) {
    return (
      <section className="secao">
        <div className="envolucro">
          <div className="aviso erro">{erro}</div>
          <Link to="/" className="botao secundario">
            Voltar para a vitrine
          </Link>
        </div>
      </section>
    );
  }

  if (!pedido) {
    return <p className="carregando envolucro">Carregando o pedido...</p>;
  }

  const emAndamento = !ESTADOS_FINAIS.includes(pedido.status);

  return (
    <section className="secao">
      <div className="envolucro">
        <div className="espalha" style={{ marginBottom: 18 }}>
          <div>
            <h1 style={{ margin: 0, fontSize: 26, letterSpacing: '-0.02em' }}>
              {pedido.situacao}
            </h1>
            <span className="mono apagado">{pedido.id}</span>
          </div>
          <span className={`selo ${seloDe(pedido.status)}`}>{legivel(pedido.status)}</span>
        </div>

        {erro && <div className="aviso erro">{erro}</div>}

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1.3fr) minmax(0, 1fr)',
            gap: 20,
            alignItems: 'start',
          }}
        >
          <div className="caixote">
            <h2 className="titulo-secao">Andamento</h2>
            <TrilhoDaSaga pedido={pedido} cobranca={cobranca} />
            {emAndamento && (
              <p className="apagado" style={{ marginTop: 4 }}>
                Atualizando a cada segundo enquanto a saga anda.
              </p>
            )}
          </div>

          <div className="caixote">
            <h2 className="titulo-secao">Itens</h2>
            <div className="rolagem">
              <table className="tabela">
                <tbody>
                  {pedido.itens.map((item) => (
                    <tr key={item.sku}>
                      <td>
                        <div>{item.descricao}</div>
                        <span className="mono apagado">{item.sku}</span>
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        {item.quantidade} x {item.precoUnitario}
                        <div className="apagado">{item.subtotal}</div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="espalha" style={{ marginTop: 14 }}>
              <span className="apagado">total</span>
              <span className="numero-grande">{pedido.total}</span>
            </div>

            {emAndamento && (
              <button
                className="botao secundario largo"
                style={{ marginTop: 16 }}
                onClick={() => void cancelar()}
                disabled={cancelando}
              >
                {cancelando ? 'Cancelando...' : 'Cancelar pedido'}
              </button>
            )}
          </div>
        </div>

        <p style={{ marginTop: 20 }}>
          <Link to="/pedidos" className="botao secundario">
            Ver todos os pedidos
          </Link>
        </p>
      </div>
    </section>
  );
}

export function seloDe(status: Pedido['status']): string {
  if (status === 'CONFIRMADO') return 'ok';
  if (status === 'CANCELADO') return 'ruim';
  return 'andamento';
}

export function legivel(status: Pedido['status']): string {
  return status.replaceAll('_', ' ').toLowerCase();
}
