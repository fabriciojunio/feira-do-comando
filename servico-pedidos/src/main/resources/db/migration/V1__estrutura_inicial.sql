-- Servico de pedidos.
--
-- Convencoes do esquema, iguais nos tres servicos:
--   * identificador uuid gerado pela aplicacao, porque o agregado ja nasce com
--     identidade antes de existir linha;
--   * dinheiro em bigint de centavos, nunca numeric solto;
--   * instante em timestamptz, sempre.

create table pedidos (
    id                    uuid         primary key,
    cliente_id            varchar(80)  not null,
    total_centavos        bigint       not null check (total_centavos >= 0),
    status                varchar(30)  not null,
    reserva_id            uuid,
    pagamento_id          uuid,
    motivo_cancelamento   varchar(40),
    detalhe_cancelamento  varchar(300),
    criado_em             timestamptz  not null,
    atualizado_em         timestamptz  not null,
    versao                bigint       not null default 0
);

create index pedidos_por_cliente on pedidos (cliente_id, criado_em desc);

-- A varredura de saga parada consulta exatamente por (status, atualizado_em).
-- O indice parcial cobre so o que interessa: pedido finalizado nunca entra
-- nessa consulta, e mantendo-o fora a arvore fica pequena mesmo com anos de
-- historico.
create index pedidos_em_aberto on pedidos (status, atualizado_em)
    where status in ('RECEBIDO', 'ESTOQUE_RESERVADO', 'CANCELANDO');

create table pedido_itens (
    pedido_id               uuid         not null references pedidos (id) on delete cascade,
    posicao                 int          not null,
    sku                     varchar(60)  not null,
    descricao               varchar(200) not null,
    quantidade              int          not null check (quantidade > 0),
    preco_unitario_centavos bigint       not null check (preco_unitario_centavos >= 0),
    primary key (pedido_id, posicao)
);

-- Outbox: a tabela mais quente do servico.
create table outbox (
    id            uuid         primary key,
    topico        varchar(120) not null,
    chave         varchar(80)  not null,
    tipo          varchar(60)  not null,
    carga         text         not null,
    criada_em     timestamptz  not null,
    publicada_em  timestamptz,
    tentativas    int          not null default 0,
    ultimo_erro   varchar(500),
    tentar_apos   timestamptz  not null
);

-- O publicador busca so o que esta pendente e ja passou da hora de tentar.
-- Indice parcial porque, em regime, quase tudo na tabela ja foi publicado, e
-- indexar linha publicada seria pagar por dado que nunca e consultado.
create index outbox_pendentes on outbox (tentar_apos, criada_em)
    where publicada_em is null;

-- Usado pelo expurgo por retencao.
create index outbox_publicadas on outbox (publicada_em) where publicada_em is not null;

-- Inbox: memoria do que ja foi consumido.
-- A chave primaria e o proprio id do evento. E ela, e nao a consulta previa,
-- que garante a idempotencia sob concorrencia.
create table eventos_processados (
    evento_id     uuid         primary key,
    tipo          varchar(60)  not null,
    correlacao    uuid         not null,
    processado_em timestamptz  not null,
    resultado     varchar(300)
);

create index eventos_por_correlacao on eventos_processados (correlacao, processado_em);
create index eventos_por_data on eventos_processados (processado_em);

-- Chave de idempotencia da API.
-- A unicidade e por (chave, cliente): dois clientes podem escolher o mesmo
-- texto por acaso, e um nao pode receber o pedido do outro.
create table chaves_idempotencia (
    id         uuid         primary key,
    chave      varchar(120) not null,
    cliente_id varchar(80)  not null,
    pedido_id  uuid         not null references pedidos (id),
    criada_em  timestamptz  not null,
    constraint chave_por_cliente unique (chave, cliente_id)
);

create index chaves_por_data on chaves_idempotencia (criada_em);
