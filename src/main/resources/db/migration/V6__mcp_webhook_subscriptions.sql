create table if not exists public.mcp_webhook_subscriptions (
    id uuid primary key,
    callback_url varchar(2048) not null,
    event_types varchar(1024) not null,
    description varchar(255),
    status varchar(24) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.mcp_webhook_deliveries (
    id uuid primary key,
    subscription_id uuid not null references public.mcp_webhook_subscriptions(id) on delete cascade,
    event_id varchar(255) not null,
    event_type varchar(64) not null,
    payload jsonb not null,
    status varchar(24) not null default 'PENDING',
    attempt int not null default 0,
    next_retry_at timestamptz not null default now(),
    response_code int,
    last_error text,
    created_at timestamptz not null default now(),
    delivered_at timestamptz,
    unique (subscription_id, event_id, event_type)
);

create index if not exists idx_mcp_webhook_delivery_due
    on public.mcp_webhook_deliveries(status, next_retry_at);
