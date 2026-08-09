alter table public.notifications
    add column if not exists trace_id text,
    add column if not exists correlation_id text,
    add column if not exists causation_id text,
    add column if not exists source_type text,
    add column if not exists source_id text,
    add column if not exists source_version integer;

create index if not exists notifications_correlation_idx
    on public.notifications(correlation_id, created_at desc);
