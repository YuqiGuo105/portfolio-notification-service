-- Operational alert recipients are deliberately separate from public topic
-- preferences. They are managed only through protected admin APIs.
create table if not exists public.admin_alert_subscriptions (
    subscriber_id uuid primary key references public.subscribers(id) on delete cascade,
    enabled       boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index if not exists admin_alert_subscriptions_enabled_idx
    on public.admin_alert_subscriptions(enabled) where enabled = true;

alter table public.admin_alert_subscriptions enable row level security;

drop trigger if exists trg_admin_alert_subscriptions_touch on public.admin_alert_subscriptions;
create trigger trg_admin_alert_subscriptions_touch
    before update on public.admin_alert_subscriptions
    for each row execute function public.tg_touch_updated_at();

-- One logical event owns one notification. Kafka or HTTP retries reuse it.
create unique index if not exists notifications_event_audit_unique
    on public.notifications(event_audit_id)
    where event_audit_id is not null;
