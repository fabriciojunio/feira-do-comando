/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    // Em desenvolvimento o navegador fala com o Vite, que repassa para os
    // servicos. Sem isso seria preciso liberar CORS em tres backends, e CORS
    // aberto em desenvolvimento tem o costume de vazar para producao.
    proxy: {
      '/api/v1/pedidos': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/v1/estoque': { target: 'http://localhost:8082', changeOrigin: true },
      '/api/v1/cobrancas': { target: 'http://localhost:8083', changeOrigin: true },
    },
  },
  build: {
    // Source map em producao entrega o codigo original para quem abrir o
    // inspetor. Nao ha ganho que compense.
    sourcemap: false,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/preparacao-dos-testes.ts',
  },
});
