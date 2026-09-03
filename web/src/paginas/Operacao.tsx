import { useEffect, useState } from 'react';
import { cobrancas, emReais, estoque, FalhaDaApi, type Saldo } from '@/servicos/api';
import { CATALOGO } from '@/servicos/catalogo';

/**
 * Painel de quem opera a loja.
 *
 * Junta o que os três serviços sabem: saldo por SKU, reservas em aberto e o
 * resumo financeiro. Não existe consulta única que devolva isso, porque cada
 * serviço tem o próprio banco. Juntar aqui, no cliente, é a consequência
 * assumida de não compartilhar tabela entre serviços.
 */
export function Operacao() {
  const [saldos, setSaldos] = useState<Saldo[]>([]);
  const [reservas, setReservas] = useState<Record<string, number>>({});
  const [financeiro, setFinanceiro] = useState<Record<string, number>>({});
  const [erro, setErro] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(true);
  const [abastecendo, setAbastecendo] = useState(false);
  const [mensagem, setMensagem] = useState<string | null>(null);

  useEffect(() => {
    void recarregar();
  }, []);

  async function recarregar() {
    setCarregando(true);
    const falhas: string[] = [];

    // Cada consulta é independente: um serviço fora do ar não pode apagar o
    // painel inteiro.
    await Promise.all([
      estoque.itens().then(setSaldos).catch(() => falhas.push('estoque')),
      estoque.resumo().then(setReservas).catch(() => falhas.push('reservas')),
      cobrancas.resumo().then(setFinanceiro).catch(() => falhas.push('pagamentos')),
    ]);

    setErro(falhas.length > 0 ? `Sem resposta de: ${falhas.join(', ')}.` : null);
    setCarregando(false);
  }

  /** Cadastra o catálogo inteiro de uma vez, para a demonstração começar. */
  async function abastecer() {
    setAbastecendo(true);
    setMensagem(null);
    try {
      for (const produto of CATALOGO) {
        await estoque.cadastrar(produto.sku, produto.nome, 10);
      }
      setMensagem('Dez unidades cadastradas para cada produto.');
      await recarregar();
    } catch (e) {
      setErro(e instanceof FalhaDaApi ? e.message : 'Não foi possível abastecer.');
    } finally {
      setAbastecendo(false);
    }
  }

  const totalAprovado = financeiro.totalAprovadoCentavos ?? 0;

  return (
    <section className="secao">
      <div className="envolucro">
        <div className="espalha" style={{ marginBottom: 18 }}>
          <h1 style={{ margin: 0, fontSize: 24 }}>Operação</h1>
          <div className="linha-flex">
            <button className="botao secundario" onClick={() => void recarregar()}>
              atualizar
            </button>
            <button
              className="botao laranja"
              onClick={() => void abastecer()}
              disabled={abastecendo}
            >
              {abastecendo ? 'abastecendo...' : 'abastecer 10 de cada'}
            </button>
          </div>
        </div>

        {erro && <div className="aviso erro">{erro}</div>}
        {mensagem && <div className="aviso ok">{mensagem}</div>}
        {carregando && <p className="carregando">Consultando os serviços...</p>}

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: 16,
            marginBottom: 22,
          }}
        >
          <Indicador titulo="reservas ativas" valor={reservas.ativas ?? 0} tom="andamento" />
          <Indicador titulo="reservas baixadas" valor={reservas.baixadas ?? 0} tom="ok" />
          <Indicador titulo="reservas liberadas" valor={reservas.liberadas ?? 0} tom="ruim" />
          <Indicador titulo="cobranças aprovadas" valor={financeiro.aprovadas ?? 0} tom="ok" />
          <Indicador titulo="cobranças recusadas" valor={financeiro.recusadas ?? 0} tom="ruim" />
          <Indicador titulo="estornos" valor={financeiro.estornadas ?? 0} tom="ruim" />
        </div>

        <div className="caixote" style={{ marginBottom: 22 }}>
          <div className="espalha">
            <span className="titulo-secao" style={{ margin: 0 }}>
              Aprovado no período
            </span>
            <span className="numero-grande" style={{ color: 'var(--folha)' }}>
              {emReais(totalAprovado)}
            </span>
          </div>
        </div>

        <h2 className="titulo-secao">Saldo por produto</h2>
        <div className="caixote rolagem">
          <table className="tabela">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Produto</th>
                <th style={{ textAlign: 'right' }}>Disponível</th>
                <th style={{ textAlign: 'right' }}>Reservado</th>
                <th style={{ textAlign: 'right' }}>Vendido</th>
                <th style={{ textAlign: 'right' }}>No galpão</th>
              </tr>
            </thead>
            <tbody>
              {saldos.map((saldo) => (
                <tr key={saldo.sku}>
                  <td className="mono">{saldo.sku}</td>
                  <td>{saldo.descricao}</td>
                  <td style={{ textAlign: 'right' }}>
                    {saldo.disponivel === 0 ? (
                      <span className="selo ruim">esgotado</span>
                    ) : (
                      saldo.disponivel
                    )}
                  </td>
                  <td style={{ textAlign: 'right' }} className="fraco">
                    {saldo.reservado}
                  </td>
                  <td style={{ textAlign: 'right' }} className="fraco">
                    {saldo.vendido}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <strong>{saldo.emMaos}</strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {saldos.length === 0 && !carregando && (
            <p className="fraco" style={{ marginBottom: 0 }}>
              Nenhum produto cadastrado ainda.
            </p>
          )}
        </div>

        <p className="apagado" style={{ marginTop: 12 }}>
          Disponível mais reservado é o que existe no galpão. Vendido já saiu. A soma dos três
          nunca muda sozinha, e é isso que o teste de concorrência verifica.
        </p>
      </div>
    </section>
  );
}

function Indicador({
  titulo,
  valor,
  tom,
}: {
  titulo: string;
  valor: number;
  tom: 'ok' | 'ruim' | 'andamento';
}) {
  const cor =
    tom === 'ok' ? 'var(--folha)' : tom === 'ruim' ? 'var(--tijolo)' : 'var(--abobora)';

  return (
    <div className="caixote">
      <div className="apagado">{titulo}</div>
      <div className="numero-grande" style={{ color: cor }}>
        {valor}
      </div>
    </div>
  );
}
