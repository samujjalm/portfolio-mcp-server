-- Trading / portfolio domain.
-- Holdings are modelled as a FIFO chain of buy "lots"; a sell consumes the
-- oldest open lots first. A position's average price is therefore derived from
-- the remaining open lots rather than stored directly.

create table customer (
    id            bigint generated always as identity primary key,
    name          varchar(255)  not null,
    cash_balance  numeric(19, 4) not null default 0,
    created_at    timestamptz   not null default now()
);

create table instrument (
    symbol      varchar(16)   primary key,
    name        varchar(255)  not null,
    isin        varchar(12)   not null unique,   -- International Securities Identification Number
    wkn         varchar(6),                      -- German Wertpapierkennnummer (nullable: not all instruments have one)
    last_price  numeric(19, 4) not null
);

create table holding_lot (
    id                 bigint generated always as identity primary key,
    customer_id        bigint        not null references customer (id),
    symbol             varchar(16)   not null references instrument (symbol),
    buy_price          numeric(19, 4) not null,
    original_quantity  integer       not null check (original_quantity > 0),
    remaining_quantity integer       not null,
    acquired_at        timestamptz   not null default now(),
    constraint chk_remaining check (remaining_quantity >= 0 and remaining_quantity <= original_quantity)
);

-- FIFO consumption order: oldest lot first, id as a stable tie-breaker.
create index idx_holding_lot_fifo on holding_lot (customer_id, symbol, acquired_at, id);

create table trade (
    id           bigint generated always as identity primary key,
    customer_id  bigint        not null references customer (id),
    symbol       varchar(16)   not null references instrument (symbol),
    side         varchar(4)    not null check (side in ('BUY', 'SELL')),
    quantity     integer       not null check (quantity > 0),
    price        numeric(19, 4) not null,        -- fill price per share
    realized_pnl numeric(19, 4),                 -- set on SELL only (FIFO cost basis vs proceeds)
    executed_at  timestamptz   not null default now()
);

create index idx_trade_customer on trade (customer_id, executed_at desc);
