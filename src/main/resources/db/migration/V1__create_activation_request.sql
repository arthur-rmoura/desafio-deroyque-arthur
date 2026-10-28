create table activation_request (
    id              uuid                     not null,
    external_id     varchar(64)              not null,
    client_id       varchar(128)             not null,
    customer_id     varchar(64)              not null,
    product_code    varchar(64)              not null,
    status          varchar(16)              not null,
    attempts        integer                  not null,
    next_attempt_at timestamp with time zone,
    failure_reason  varchar(255),
    correlation_id  varchar(64)              not null,
    created_at      timestamp with time zone not null,
    updated_at      timestamp with time zone not null,
    version         bigint                   not null,
    constraint pk_activation_request primary key (id),
    -- Garantia definitiva contra duplicidade: o banco rejeita o segundo insert mesmo sob concorrência.
    constraint uk_activation_request_external_id unique (external_id),
    constraint ck_activation_request_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listagem paginada: sempre filtrada pelo consumidor (isolamento), opcionalmente por status ou cliente,
-- ordenada por data de criação.
create index idx_activation_request_client_status_created
    on activation_request (client_id, status, created_at desc);

create index idx_activation_request_client_customer_created
    on activation_request (client_id, customer_id, created_at desc);

-- Busca de solicitações elegíveis para envio ao provedor (estados finais têm next_attempt_at nulo).
create index idx_activation_request_next_attempt
    on activation_request (next_attempt_at);
