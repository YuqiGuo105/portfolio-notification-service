alter table public.subscribers
    add column if not exists unsubscribed_at timestamptz,
    add column if not exists unsubscribe_source text;

create index if not exists subscribers_unsubscribed_at_idx
    on public.subscribers(unsubscribed_at)
    where status = 'UNSUBSCRIBED';
