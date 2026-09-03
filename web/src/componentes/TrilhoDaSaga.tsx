import type { Cobranca, Pedido } from '@/servicos/api';

/**
 * Mostra onde o pedido está dentro da saga.
 *
 * Esta é a tela que explica o sistema. Um pedido "em processamento" não diz
 * nada; ver que o estoque já separou e que a cobrança está em andamento diz
 * tudo, e reduz pela metade a vontade de ligar para o suporte.
 *
 * Os passos são derivados do status, e não de um histórico vindo do servidor.
 * O motivo é simples: o status é a fonte de verdade da saga, e montar a
 * narrativa a partir dele garante que a tela nunca conte uma história
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
        titulo: 'Separação no estoque',
        detalhe: pedido.detalheDoCancelamento ?? 'Sem saldo disponível',
        situacao: 'falhou' as Situacao,
      };
    }
    if (status === 'RECEBIDO') {
      return {
        titulo: 'Separação no estoque',
        detalhe: 'Separando os itens',
        situacao: 'agora' as Situacao,
      };
    }
    return {
      titulo: 'Separação no estoque',
      detalhe: 'Itens separados',
      situacao: 'concluido' as Situacao,
    };
  })();

  const pagamento: Passo = (() => {
    if (semEstoque) {
      return {
        titulo: 'Cobrança',
        detalhe: 'Não chegou a ser cobrado',
        situacao: 'esperando' as Situacao,
      };
    }
    if (cobranca?.situacao === 'RECUSADA') {
      return {
        titulo: 'Cobrança',
        detalhe: `Recusada: ${legivel(cobranca.motivoDaRecusa)}`,
        situacao: 'falhou' as Situacao,
      };
    }
    if (cobranca?.situacao === 'ESTORNADA') {
      return {
        titulo: 'Cobrança',
        detalhe: `Aprovada e depois estornada (${cobranca.valor})`,
        situacao: 'falhou' as Situacao,
      };
    }
    if (status === 'CONFIRMADO' || cobranca?.situacao === 'APROVADA') {
      return {
        titulo: 'Cobrança',
        detalhe: cobranca?.referenciaExterna
          ? `Aprovada, transação ${cobranca.referenciaExterna}`
          : 'Aprovada',
        situacao: 'concluido' as Situacao,
      };
    }
    if (status === 'ESTOQUE_RESERVADO') {
      return {
        titulo: 'Cobrança',
        detalhe: 'Autorizando o pagamento',
        situacao: 'agora' as Situacao,
      };
    }
    return { titulo: 'Cobrança', detalhe: 'Aguardando', situacao: 'esperando' as Situacao };
  })();

  const passos: Passo[] = [recebido, reserva, pagamento];

  if (cancelando) {
    passos.push({
      titulo: 'Devolvendo o que foi separado',
      detalhe: 'A compensação está em andamento',
      situacao: 'agora',
    });
  }

  passos.push({
    titulo: cancelado ? 'Pedido cancelado' : 'Pedido confirmado',
    detalhe: cancelado
      ? (pedido.detalheDoCancelamento ?? legivel(pedido.motivoDoCancelamento))
      : status === 'CONFIRMADO'
        ? 'Tudo certo, seu pedido está fechado'
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
