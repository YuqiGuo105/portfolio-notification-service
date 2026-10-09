create table public.subscription_confirmations (
    email text primary key,
    token_hash text not null unique,
    topics text not null,
    channels text not null,
    requested_at timestamptz not null default now(),
    expires_at timestamptz not null,
    consumed_at timestamptz
);
alter table public.subscription_confirmations enable row level security;
revoke all on public.subscription_confirmations from public, anon, authenticated;
