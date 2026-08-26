import { NavLink, Route, Routes } from 'react-router-dom';
import { Vitrine } from '@/paginas/Vitrine';
import { MeusPedidos } from '@/paginas/MeusPedidos';
import { DetalheDoPedido } from '@/paginas/DetalheDoPedido';
import { Operacao } from '@/paginas/Operacao';

export function App() {
  return (
    <>
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

function NaoEncontrado() {
  return (
    <section className="secao">
      <div className="envolucro">
        <div className="aviso">Pagina nao encontrada.</div>
      </div>
    </section>
  );
}
