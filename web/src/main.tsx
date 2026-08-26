import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import './estilos/global.css';

const raiz = document.getElementById('raiz');
if (!raiz) {
  throw new Error('elemento raiz nao encontrado');
}

createRoot(raiz).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
