import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { pedidos, FalhaDaApi, type Pedido } from '@/servicos/api';
import { legivel, seloDe } from '@/paginas/DetalheDoPedido';

export function MeusPedidos() {
  const [lista, setLista] = useState<Pedido[]>([]);
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState<string | null>(null);

  useEffect(() => {
    pedidos
      .listar(50)
      .then((pagina) => setLista(pagina.conteudo))
      .catch((e: unknown) =>
        setErro(e instanceof FalhaDaApi ? e.message : 'Nao foi possivel listar os pedidos.'),
      )
      .finally(() => setCarregando(false));
  }, []);

  return (
    <section className="secao">
      <div className="envolucro">
        <h1 style={{ margin: '0 0 18px', fontSize: 24 }}>Meus pedidos</h1>

        {erro && <div className="aviso erro">{erro}</div>}
        {carregando && <p className="carregando">Carregando...</p>}

        {!carregando && lista.length === 0 && !erro && (
          <div className="aviso">
            Nenhum pedido ainda. <Link to="/">Comece pela vitrine.</Link>
          </div>
        )}

        {lista.length > 0 && (
          <div className="caixote rolagem">
            <table className="tabela">
              <thead>
                <tr>
                  <th>Pedido</th>
                  <th>Itens</th>
                  <th>Total</th>
                  <th>Situacao</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {lista.map((pedido) => (
                  <tr key={pedido.id}>
                    <td>
                      <span className="mono">{pedido.id.slice(0, 8)}</span>
                      <div className="apagado">{pedido.criadoEm.slice(0, 16).replace('T', ' ')}</div>
                    </td>
                    <td className="fraco">
                      {pedido.itens.map((i) => `${i.quantidade}x ${i.sku}`).join(', ')}
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>{pedido.total}</td>
                    <td>
                      <span className={`selo ${seloDe(pedido.status)}`}>
                        {legivel(pedido.status)}
                      </span>
                      {pedido.motivoDoCancelamento && (
                        <div className="apagado">
                          {pedido.motivoDoCancelamento.replaceAll('_', ' ').toLowerCase()}
                        </div>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <Link to={`/pedidos/${pedido.id}`} className="botao secundario pequeno">
                        acompanhar
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
