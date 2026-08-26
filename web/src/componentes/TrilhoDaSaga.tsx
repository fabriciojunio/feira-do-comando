import type { Cobranca, Pedido } from '@/servicos/api';

/**
 * Mostra onde o pedido esta dentro da saga.
 *
 * Esta e a tela que explica o sistema. Um pedido "em processamento" nao diz
 * nada; ver que o estoque ja separou e que a cobranca esta em andamento diz
 * tudo, e reduz pela metade a vontade de ligar para o suporte.
 *
 * Os passos sao derivados do status, e nao de um historico vindo do servidor.
 * O motivo e simples: o status e a fonte de verdade da saga, e montar a
 * narrativa a partir dele garante que a tela nunca conte uma historia
 * diferente da que o banco guarda.
 */

type Situacao = 'esperando' | 'agora' | 'concluido' | 'falhou';

type Passo = {
  titulo: string;
  detalhe: string;
  situacao: Situacao;
};

function classeDe(situacao: Situacao): string {
  return situacao === 'esperando' ? 'passo' : `passo ${situacao}`;
}

function marcadorDe(situacao: Situacao, posicao: number): string {
  if (situacao === 'concluido') return '✓';
  if (situacao === 'falhou') return '✕';
  return String(posicao);
}

export function montarPassos(pedido: Pedido, cobranca: Cobranca | null): Passo[] {
  const status = pedido.status;
  const cancelado = status === 'CANCELADO';
  const cancelando = status === 'CANCELANDO';
  const semEstoque = pedido.motivoDoCancelamento === 'SEM_ESTOQUE';

  const recebido: Passo = {
    titulo: 'Pedido recebido',
    detalhe: `${pedido.itens.length} ${pedido.itens.length === 1 ? 'item' : 'itens'}, ${pedido.total}`,
    situacao: 'concluido',
  };

  const reserva: Passo = (() => {
    if (semEstoque) {
      return {
        titulo: 'Separacao no estoque',
        detalhe: pedido.detalheDoCancelamento ?? 'Sem saldo disponivel',
        situacao: 'falhou' as Situacao,
      };
    }
    if (status === 'RECEBIDO') {
      return {
        titulo: 'Separacao no estoque',
        detalhe: 'Separando os itens',
        situacao: 'agora' as Situacao,
      };
    }
    return {
      titulo: 'Separacao no estoque',
      detalhe: 'Itens separados',
      situacao: 'concluido' as Situacao,
    };
  })();

  const pagamento: Passo = (() => {
    if (semEstoque) {
      return {
        titulo: 'Cobranca',
        detalhe: 'Nao chegou a ser cobrado',
        situacao: 'esperando' as Situacao,
      };
    }
    if (cobranca?.situacao === 'RECUSADA') {
      return {
        titulo: 'Cobranca',
        detalhe: `Recusada: ${legivel(cobranca.motivoDaRecusa)}`,
        situacao: 'falhou' as Situacao,
      };
    }
    if (cobranca?.situacao === 'ESTORNADA') {
      return {
        titulo: 'Cobranca',
        detalhe: `Aprovada e depois estornada (${cobranca.valor})`,
        situacao: 'falhou' as Situacao,
      };
    }
    if (status === 'CONFIRMADO' || cobranca?.situacao === 'APROVADA') {
      return {
        titulo: 'Cobranca',
        detalhe: cobranca?.referenciaExterna
          ? `Aprovada, transacao ${cobranca.referenciaExterna}`
          : 'Aprovada',
        situacao: 'concluido' as Situacao,
      };
    }
    if (status === 'ESTOQUE_RESERVADO') {
      return {
        titulo: 'Cobranca',
        detalhe: 'Autorizando o pagamento',
        situacao: 'agora' as Situacao,
      };
    }
    return { titulo: 'Cobranca', detalhe: 'Aguardando', situacao: 'esperando' as Situacao };
  })();

  const passos: Passo[] = [recebido, reserva, pagamento];

  if (cancelando) {
    passos.push({
      titulo: 'Devolvendo o que foi separado',
      detalhe: 'A compensacao esta em andamento',
      situacao: 'agora',
    });
  }

  passos.push({
    titulo: cancelado ? 'Pedido cancelado' : 'Pedido confirmado',
    detalhe: cancelado
      ? (pedido.detalheDoCancelamento ?? legivel(pedido.motivoDoCancelamento))
      : status === 'CONFIRMADO'
        ? 'Tudo certo, seu pedido esta fechado'
        : 'Aguardando os passos anteriores',
    situacao: cancelado ? 'falhou' : status === 'CONFIRMADO' ? 'concluido' : 'esperando',
  });

  return passos;
}

function legivel(codigo: string | null | undefined): string {
  if (!codigo) return 'sem detalhe';
  return codigo.replaceAll('_', ' ').toLowerCase();
}

export function TrilhoDaSaga({
  pedido,
  cobranca,
}: {
  pedido: Pedido;
  cobranca: Cobranca | null;
}) {
  const passos = montarPassos(pedido, cobranca);

  return (
    <ol className="trilho" aria-label="Andamento do pedido">
      {passos.map((passo, indice) => (
        <li key={passo.titulo} className={classeDe(passo.situacao)}>
          <span className="marcador" aria-hidden="true">
            {marcadorDe(passo.situacao, indice + 1)}
          </span>
          <span>
            <span className="titulo">{passo.titulo}</span>
            <span className="detalhe" style={{ display: 'block' }}>
              {passo.detalhe}
            </span>
          </span>
        </li>
      ))}
    </ol>
  );
}
