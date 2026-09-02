/**
 * Erro de API no formato RFC 9457.
 *
 * Mora num modulo proprio, e nao dentro do cliente, para o cliente HTTP e a
 * saga simulada poderem usar a mesma classe sem um importar o outro. Duas
 * classes de erro fariam a tela ter que tratar as duas, e uma delas seria
 * esquecida.
 *
 * O campo `codigo` e uma extensao nossa e existe justamente para a interface
 * poder ramificar sem depender do texto, que muda.
 */
export class FalhaDaApi extends Error {
  constructor(
    readonly codigo: string,
    mensagem: string,
    readonly status: number,
    readonly detalhes: Record<string, unknown> = {},
  ) {
    super(mensagem);
    this.name = 'FalhaDaApi';
  }
}
