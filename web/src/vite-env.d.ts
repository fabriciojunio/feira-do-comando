/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base da API. Vazio em desenvolvimento, porque o proxy do Vite resolve. */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
