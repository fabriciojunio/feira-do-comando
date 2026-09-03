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
              Operação
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
            Feira. Três serviços, um tópico entre eles e uma saga que precisa sobreviver a
            mensagem repetida.
          </span>
          <span className="mono">v0.1.0</span>
        </div>
      </footer>
    </>
  );
}

/**
 * Diz que isto é uma demonstração, e diz onde está o sistema.
 *
 * Fica no topo e não no rodapé de propósito: quem abre a página precisa saber
 * antes de clicar, e não depois de rolar até o fim. Demonstração que não se
 * anuncia vira promessa que o sistema não cumpre.
 */
function FaixaDaDemonstracao() {
  return (
    <div className="faixa-demonstracao">
      <div className="envolucro">
        <strong>Modo demonstração.</strong> A saga roda aqui no navegador, com as mesmas
        transições do sistema: reserva de estoque, cobrança, e a compensação quando o cartão
        recusa. Não há back-end atrás desta página, porque quatro serviços Java, um Kafka e três
        bancos não cabem em camada gratuita. O sistema de verdade sobe com{' '}
        <code>docker compose up -d</code>.{' '}
        <a
          href="https://github.com/fabriciojunio/feira-do-comando"
          target="_blank"
          rel="noopener noreferrer"
        >
          Código no GitHub
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
        <div className="aviso">Página não encontrada.</div>
      </div>
    </section>
  );
}
