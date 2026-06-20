-- ============================================================================
-- Portfolio Notification Service - initial schema
-- Apply via Flyway (runs automatically on service startup) OR paste into the
-- Supabase SQL editor.
--
-- This migration is additive and does NOT touch existing Portfolio tables
-- (Blogs, life_blogs, cv_content, etc.). All names are unprefixed in the
-- public schema because none of them collide with the existing portfolio
-- schema today.
-- ============================================================================

create extension if not exists "pgcrypto";

-- ----------------------------------------------------------------------------
-- subscribers
-- ----------------------------------------------------------------------------
create table if not exists public.subscribers (
    id                       uuid primary key default gen_random_uuid(),
    email                    text not null unique,
    status                   text not null default 'ACTIVE',
    subscriber_token_hash    text not null,
    unsubscribe_token_hash   text,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    constraint subscribers_status_chk check (status in ('ACTIVE','UNSUBSCRIBED','BOUNCED'))
);

create index if not exists subscribers_status_idx on public.subscribers(status);
create index if not exists subscribers_unsub_hash_idx on public.subscribers(unsubscribe_token_hash);

-- ----------------------------------------------------------------------------
-- subscription_preferences
-- ----------------------------------------------------------------------------
create table if not exists public.subscription_preferences (
    id              uuid primary key default gen_random_uuid(),
    subscriber_id   uuid not null references public.subscribers(id) on delete cascade,
    topic           text not null,
    email_enabled   boolean not null default true,
    web_enabled     boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint subscription_preferences_topic_chk
        check (topic in ('ARTICLE_UPDATES','FEATURE_UPDATES','JOB_UPDATES')),
    constraint subscription_preferences_unique unique (subscriber_id, topic)
);

create index if not exists subscription_preferences_topic_idx
    on public.subscription_preferences(topic)
    where email_enabled = true or web_enabled = true;

-- ----------------------------------------------------------------------------
-- content_event_audit
-- ----------------------------------------------------------------------------
create table if not exists public.content_event_audit (
    id                uuid primary key default gen_random_uuid(),
    kafka_topic       text,
    kafka_partition   int,
    kafka_offset      text,
    event_id          text,
    event_type        text,
    topic             text,
    source_type       text,
    source_id         text,
    title             text,
    summary           text,
    url               text,
    -- Stored as text (JSON-serialized). Switch to jsonb later via:
    --   alter table public.content_event_audit
    --     alter column payload type jsonb using payload::jsonb;
    payload           text,
    status            text not null default 'PROCESSING',
    retry_count       int not null default 0,
    last_error        text,
    idempotency_key   text not null unique,
    created_at        timestamptz not null default now(),
    processed_at      timestamptz,
    updated_at        timestamptz not null default now(),
    constraint content_event_audit_status_chk
        check (status in ('PROCESSING','DONE','FAILED','DLQ'))
);

create index if not exists content_event_audit_status_idx
    on public.content_event_audit(status);
create index if not exists content_event_audit_topic_idx
    on public.content_event_audit(topic);

-- ----------------------------------------------------------------------------
-- notifications (one per content event after fan-out)
-- ----------------------------------------------------------------------------
create table if not exists public.notifications (
    id              uuid primary key default gen_random_uuid(),
    event_audit_id  uuid references public.content_event_audit(id) on delete set null,
    topic           text not null,
    title           text not null,
    body            text,
    url             text,
    created_at      timestamptz not null default now()
);

create index if not exists notifications_topic_idx on public.notifications(topic);
create index if not exists notifications_created_idx on public.notifications(created_at desc);

-- ----------------------------------------------------------------------------
-- notification_recipients (fan-out rows: one per subscriber x channel)
-- ----------------------------------------------------------------------------
create table if not exists public.notification_recipients (
    id                uuid primary key default gen_random_uuid(),
    notification_id   uuid not null references public.notifications(id) on delete cascade,
    subscriber_id     uuid not null references public.subscribers(id) on delete cascade,
    channel           text not null,
    status            text not null default 'PENDING',
    retry_count       int not null default 0,
    next_retry_at     timestamptz,
    sent_at           timestamptz,
    read_at           timestamptz,
    last_error        text,
    idempotency_key   text not null unique,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint notification_recipients_channel_chk
        check (channel in ('WEB','EMAIL')),
    constraint notification_recipients_status_chk
        check (status in ('PENDING','SENT','FAILED','READ','SKIPPED'))
);

create index if not exists notification_recipients_subscriber_idx
    on public.notification_recipients(subscriber_id, channel, status, created_at desc);
create index if not exists notification_recipients_dispatch_idx
    on public.notification_recipients(channel, status, next_retry_at)
    where channel = 'EMAIL' and status in ('PENDING','FAILED');

-- ----------------------------------------------------------------------------
-- updated_at trigger
-- ----------------------------------------------------------------------------
create or replace function public.tg_touch_updated_at()
returns trigger language plpgsql as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_subscribers_touch on public.subscribers;
create trigger trg_subscribers_touch
    before update on public.subscribers
    for each row execute function public.tg_touch_updated_at();

drop trigger if exists trg_subscription_preferences_touch on public.subscription_preferences;
create trigger trg_subscription_preferences_touch
    before update on public.subscription_preferences
    for each row execute function public.tg_touch_updated_at();

drop trigger if exists trg_content_event_audit_touch on public.content_event_audit;
create trigger trg_content_event_audit_touch
    before update on public.content_event_audit
    for each row execute function public.tg_touch_updated_at();

drop trigger if exists trg_notification_recipients_touch on public.notification_recipients;
create trigger trg_notification_recipients_touch
    before update on public.notification_recipients
    for each row execute function public.tg_touch_updated_at();

-- ----------------------------------------------------------------------------
-- Row Level Security
--
-- The Spring service connects with the Postgres role (bypasses RLS), so all
-- writes happen server-side via verified tokens.
--
-- The Next.js frontend uses the Supabase anon key ONLY to subscribe to
-- Realtime INSERT events on notification_recipients. That subscription is
-- used purely as a "ping" — actual notification content is fetched via the
-- verified GET /api/notifications endpoint. The realtime row reveals no
-- payload (only ids, channel, status), so the security loss of allowing
-- anon SELECT here is acceptable for an MVP. Tighten with auth.jwt() later.
-- ----------------------------------------------------------------------------
alter table public.subscribers              enable row level security;
alter table public.subscription_preferences enable row level security;
alter table public.content_event_audit      enable row level security;
alter table public.notifications            enable row level security;
alter table public.notification_recipients  enable row level security;

-- subscribers / preferences / audit / notifications: deny anon by default
drop policy if exists "anon read recipients ping" on public.notification_recipients;
create policy "anon read recipients ping"
    on public.notification_recipients
    for select
    to anon
    using (true);

-- ----------------------------------------------------------------------------
-- Supabase Realtime publication
-- (Supabase ships with a `supabase_realtime` publication; add the table.)
-- ----------------------------------------------------------------------------
do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        begin
            execute 'alter publication supabase_realtime add table public.notification_recipients';
        exception when duplicate_object then
            -- already added
            null;
        end;
    end if;
end$$;
