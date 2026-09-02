import { NavLink, Route, Routes } from 'react-router-dom';
import { MODO_DEMONSTRACAO } from '@/servicos/api';
import { Vitrine } from '@/paginas/Vitrine';
import { MeusPedidos } from '@/paginas/MeusPedidos';
import { DetalheDoPedido } from '@/paginas/DetalheDoPedido';
import { Operacao } from '@/paginas/Operacao';

export function App() {
  return (
    <>
      {MODO_DEMONSTRACAO && <FaixaDaDemonstracao />}

      <header className="cabecalho">
        <div className="envolucro">
          <NavLink to="/" className="marca">
            <span className="toldo" aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
            Feira
          </NavLink>
          <nav className="navegacao">
            <NavLink to="/" end className={({ isActive }) => (isActive ? 'ativo' : '')}>
              Vitrine
            </NavLink>
            <NavLink to="/pedidos" className={({ isActive }) => (isActive ? 'ativo' : '')}>
              Meus pedidos
            </NavLink>
            <NavLink to="/operacao" className={({ isActive }) => (isActive ? 'ativo' : '')}>
              Operacao
            </NavLink>
          </nav>
        </div>
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Vitrine />} />
          <Route path="/pedidos" element={<MeusPedidos />} />
          <Route path="/pedidos/:id" element={<DetalheDoPedido />} />
          <Route path="/operacao" element={<Operacao />} />
          <Route path="*" element={<NaoEncontrado />} />
        </Routes>
      </main>

      <footer className="rodape">
        <div className="envolucro espalha">
          <span>
            Feira. Tres servicos, um topico entre eles e uma saga que precisa sobreviver a
            mensagem repetida.
          </span>
          <span className="mono">v0.1.0</span>
        </div>
      </footer>
    </>
  );
}

/**
 * Diz que isto e uma demonstracao, e diz onde esta o sistema.
 *
 * Fica no topo e nao no rodape de proposito: quem abre a pagina precisa saber
 * antes de clicar, e nao depois de rolar ate o fim. Demonstracao que nao se
 * anuncia vira promessa que o sistema nao cumpre.
 */
function FaixaDaDemonstracao() {
  return (
    <div className="faixa-demonstracao">
      <div className="envolucro">
        <strong>Modo demonstracao.</strong> A saga roda aqui no navegador, com as mesmas
        transicoes do sistema: reserva de estoque, cobranca, e a compensacao quando o cartao
        recusa. Nao ha back-end atras desta pagina, porque quatro servicos Java, um Kafka e tres
        bancos nao cabem em camada gratuita. O sistema de verdade sobe com{' '}
        <code>docker compose up -d</code>.{' '}
        <a
          href="https://github.com/fabriciojunio/feira-do-comando"
          target="_blank"
          rel="noopener noreferrer"
        >
          Codigo no GitHub
        </a>
        .
      </div>
    </div>
  );
}

function NaoEncontrado() {
  return (
    <section className="secao">
      <div className="envolucro">
        <div className="aviso">Pagina nao encontrada.</div>
      </div>
    </section>
  );
}
