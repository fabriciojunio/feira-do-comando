/**
 * Preco dos produtos.
 *
 * Fica no cliente de propósito, e isso e uma limitacao declarada e nao um
 * descuido: catalogo e precificacao sao um servico proprio em qualquer loja de
 * verdade, com historico de preco, promocao por periodo e regra por regiao.
 * Modelar isso aqui nao acrescentaria nada ao que este projeto existe para
 * mostrar, que e a saga.
 *
 * O saldo, esse sim, vem do servico de estoque. O que a tela combina e preco
 * local com disponibilidade real.
 */
export type Produto = {
  sku: string;
  nome: string;
  preco: string;
  unidade: string;
};

export const CATALOGO: Produto[] = [
  { sku: 'CAF-500', nome: 'Cafe torrado e moido', preco: '24.90', unidade: 'pacote 500g' },
  { sku: 'ACU-1KG', nome: 'Acucar cristal', preco: '6.20', unidade: 'pacote 1kg' },
  { sku: 'ARR-5KG', nome: 'Arroz agulhinha tipo 1', preco: '28.50', unidade: 'fardo 5kg' },
  { sku: 'FEI-1KG', nome: 'Feijao carioca', preco: '9.80', unidade: 'pacote 1kg' },
  { sku: 'OLE-900', nome: 'Oleo de soja', preco: '7.40', unidade: 'garrafa 900ml' },
  { sku: 'MAC-500', nome: 'Macarrao espaguete', preco: '4.99', unidade: 'pacote 500g' },
  { sku: 'FAR-1KG', nome: 'Farinha de trigo', preco: '5.60', unidade: 'pacote 1kg' },
  { sku: 'SAL-1KG', nome: 'Sal refinado', preco: '2.38', unidade: 'pacote 1kg' },
];

export function produtoDe(sku: string): Produto | undefined {
  return CATALOGO.find((p) => p.sku === sku);
}

/**
 * Os centavos decidem o desfecho no adquirente simulado, do mesmo jeito que o
 * cartao de teste decide em homologacao de adquirente de verdade. A vitrine
 * mostra isso para quem esta demonstrando o sistema conseguir provocar uma
 * recusa de proposito, em vez de depender de sorte.
 *
 * Isso obriga o catalogo a ter pelo menos dois precos com centavo quebrado. Com
 * todos os precos terminando em zero, nenhum total possivel cai nestes restos e
 * o recurso vira letra morta. Os caminhos curtos hoje sao:
 *
 *   1 macarrao ................. R$  4,99 -> falha temporaria
 *   7 sais ..................... R$ 16,66 -> suspeita de fraude
 *   1 macarrao + 3 sais ........ R$ 12,13 -> saldo insuficiente
 *
 * O teste `alcancaOsTresDesfechosForcados` guarda essa propriedade: mexer no
 * preco sem olhar quebra a demonstracao em silencio.
 */
export const CENTAVOS_QUE_FORCAM_RECUSA: Record<number, string> = {
  13: 'saldo insuficiente',
  66: 'suspeita de fraude',
  99: 'falha temporaria',
};

export function desfechoForcado(totalEmCentavos: number): string | undefined {
  return CENTAVOS_QUE_FORCAM_RECUSA[totalEmCentavos % 100];
}
