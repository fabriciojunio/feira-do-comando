-- Servico de pagamentos.

create table cobrancas (
    -- O id do pedido e a chave primaria. Cobrar duas vezes o mesmo pedido e o
    -- pior defeito possivel aqui, e com a mensageria isso deixa de ser
    -- hipotese. A garantia vem do banco, e nao de um if.
    pedido_id          uuid         primary key,
    id                 uuid         not null,
    valor_centavos     bigint       not null check (valor_centavos >= 0),
    situacao           varchar(20)  not null,
    referencia_externa varchar(80),
    motivo_recusa      varchar(30),
    criada_em          timestamptz  not null,
    encerrada_em       timestamptz,
    versao             bigint       not null default 0
);

-- Alerta de cobranca parada: o estoque nunca respondeu.
create index cobrancas_pendentes on cobrancas (criada_em) where situacao = 'PENDENTE';
create index cobrancas_por_situacao on cobrancas (situacao);

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
