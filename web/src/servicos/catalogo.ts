/**
 * Preço dos produtos.
 *
 * Fica no cliente de propósito, e isso é uma limitação declarada e não um
 * descuido: catálogo e precificação são um serviço próprio em qualquer loja de
 * verdade, com histórico de preço, promoção por período e regra por região.
 * Modelar isso aqui não acrescentaria nada ao que este projeto existe para
 * mostrar, que é a saga.
 *
 * O saldo, esse sim, vem do serviço de estoque. O que a tela combina é preço
 * local com disponibilidade real.
 */
export type Produto = {
  sku: string;
  nome: string;
  preco: string;
  unidade: string;
};

export const CATALOGO: Produto[] = [
  { sku: 'CAF-500', nome: 'Café torrado e moído', preco: '24.90', unidade: 'pacote 500g' },
  { sku: 'ACU-1KG', nome: 'Açúcar cristal', preco: '6.20', unidade: 'pacote 1kg' },
  { sku: 'ARR-5KG', nome: 'Arroz agulhinha tipo 1', preco: '28.50', unidade: 'fardo 5kg' },
  { sku: 'FEI-1KG', nome: 'Feijão carioca', preco: '9.80', unidade: 'pacote 1kg' },
  { sku: 'OLE-900', nome: 'Óleo de soja', preco: '7.40', unidade: 'garrafa 900ml' },
  { sku: 'MAC-500', nome: 'Macarrão espaguete', preco: '4.99', unidade: 'pacote 500g' },
  { sku: 'FAR-1KG', nome: 'Farinha de trigo', preco: '5.60', unidade: 'pacote 1kg' },
  { sku: 'SAL-1KG', nome: 'Sal refinado', preco: '2.38', unidade: 'pacote 1kg' },
];

export function produtoDe(sku: string): Produto | undefined {
  return CATALOGO.find((p) => p.sku === sku);
}

/**
 * Os centavos decidem o desfecho no adquirente simulado, do mesmo jeito que o
 * cartão de teste decide em homologação de adquirente de verdade. A vitrine
 * mostra isso para quem está demonstrando o sistema conseguir provocar uma
 * recusa de propósito, em vez de depender de sorte.
 *
 * Isso obriga o catálogo a ter pelo menos dois preços com centavo quebrado. Com
 * todos os preços terminando em zero, nenhum total possível cai nestes restos e
 * o recurso vira letra morta. Os caminhos curtos hoje são:
 *
 *   1 macarrão ................. R$  4,99 -> falha temporária
 *   7 sais ..................... R$ 16,66 -> suspeita de fraude
 *   1 macarrão + 3 sais ........ R$ 12,13 -> saldo insuficiente
 *
 * O teste `alcancaOsTresDesfechosForcados` guarda essa propriedade: mexer no
 * preço sem olhar quebra a demonstração em silêncio.
 */
export const CENTAVOS_QUE_FORCAM_RECUSA: Record<number, string> = {
  13: 'saldo insuficiente',
  66: 'suspeita de fraude',
  99: 'falha temporária',
};

export function desfechoForcado(totalEmCentavos: number): string | undefined {
  return CENTAVOS_QUE_FORCAM_RECUSA[totalEmCentavos % 100];
}
