-- Servico de estoque.

create table itens_de_estoque (
    sku           varchar(60)  primary key,
    descricao     varchar(200) not null,
    -- Os tres saldos sao separados de proposito. Um numero so obrigaria a
    -- escolher entre vender duas vezes a mesma peca e baixar o estoque antes
    -- de o pagamento existir.
    disponivel    int          not null check (disponivel >= 0),
    reservado     int          not null check (reservado  >= 0),
    vendido       int          not null check (vendido    >= 0),
    atualizado_em timestamptz  not null,
    versao        bigint       not null default 0
);

-- Consulta do painel de reposicao.
create index itens_com_pouco_saldo on itens_de_estoque (disponivel);

create table reservas (
    -- O id do pedido e a chave primaria. Reprocessar o mesmo PedidoCriado
    -- esbarra na chave e nao reserva duas vezes: a garantia vem do banco, e
    -- nao de um if no codigo.
    pedido_id     uuid         primary key,
    id            uuid         not null,
    situacao      varchar(20)  not null,
    criada_em     timestamptz  not null,
    encerrada_em  timestamptz
);

-- Alerta de mercadoria presa: reserva ativa ha tempo demais.
create index reservas_ativas on reservas (situacao, criada_em) where situacao = 'ATIVA';

create table reserva_itens (
    pedido_id  uuid        not null references reservas (pedido_id) on delete cascade,
    sku        varchar(60) not null,
    quantidade int         not null check (quantidade > 0)
);

create index reserva_itens_por_pedido on reserva_itens (pedido_id);

-- Outbox e inbox, iguais nos tres servicos porque vem do modulo compartilhado.
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

create index outbox_pendentes on outbox (tentar_apos, criada_em) where publicada_em is null;
create index outbox_publicadas on outbox (publicada_em) where publicada_em is not null;

create table eventos_processados (
    evento_id     uuid         primary key,
    tipo          varchar(60)  not null,
    correlacao    uuid         not null,
    processado_em timestamptz  not null,
    resultado     varchar(300)
);

create index eventos_por_correlacao on eventos_processados (correlacao, processado_em);
create index eventos_por_data on eventos_processados (processado_em);
