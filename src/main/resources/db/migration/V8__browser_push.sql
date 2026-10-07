create table public.browser_push_subscriptions (
    id uuid primary key,
    subscriber_id uuid not null references public.subscribers(id) on delete cascade,
    endpoint varchar(2048) not null unique,
    p256dh varchar(128) not null,
    auth varchar(64) not null,
    active boolean not null default true,
    created_at timestamptz not null default now()
);
create index browser_push_subscriber on public.browser_push_subscriptions(subscriber_id);
create table public.browser_push_deliveries (
    id uuid primary key,
    device_id uuid not null references public.browser_push_subscriptions(id) on delete cascade,
    notification_id uuid not null references public.notifications(id) on delete cascade,
    status varchar(24) not null default 'PENDING'
        check (status in ('PENDING','SENDING','SENT','RETRY','FAILED','SKIPPED','UNKNOWN')),
    attempt integer not null default 0,
    claim_token uuid,
    available_at timestamptz not null default now(),
    response_code integer,
    created_at timestamptz not null default now(),
    sent_at timestamptz,
    unique(device_id, notification_id)
);
create index browser_push_due on public.browser_push_deliveries(status, available_at);
create index browser_push_notification on public.browser_push_deliveries(notification_id);
-- Endpoints are capability URLs, accessible only through the token-verifying service.
alter table public.browser_push_subscriptions enable row level security;
alter table public.browser_push_deliveries enable row level security;
revoke all on public.browser_push_subscriptions, public.browser_push_deliveries from anon, authenticated;
