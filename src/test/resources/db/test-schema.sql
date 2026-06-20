-- H2-compatible mirror of V1__initial_schema.sql for unit/integration tests.
-- Production uses src/main/resources/db/migration/V1__initial_schema.sql on Postgres via Flyway.
-- All tables live in the "public" schema so the production SQL referencing public.* still works.

create schema if not exists public;
set schema public;

drop table if exists public.notification_recipients;
drop table if exists public.notifications;
drop table if exists public.content_event_audit;
drop table if exists public.subscription_preferences;
drop table if exists public.subscribers;

create table public.subscribers (
    id                       uuid primary key,
    email                    varchar(254) not null unique,
    status                   varchar(32) not null default 'ACTIVE',
    subscriber_token_hash    varchar(128) not null,
    unsubscribe_token_hash   varchar(128),
    created_at               timestamp with time zone not null default current_timestamp,
    updated_at               timestamp with time zone not null default current_timestamp
);

create table public.subscription_preferences (
    id              uuid primary key,
    subscriber_id   uuid not null,
    topic           varchar(64) not null,
    email_enabled   boolean not null default true,
    web_enabled     boolean not null default true,
    created_at      timestamp with time zone not null default current_timestamp,
    updated_at      timestamp with time zone not null default current_timestamp,
    constraint subscription_preferences_unique unique (subscriber_id, topic),
    foreign key (subscriber_id) references public.subscribers(id) on delete cascade
);

create table public.content_event_audit (
    id                uuid primary key,
    kafka_topic       varchar(255),
    kafka_partition   int,
    kafka_offset      varchar(64),
    event_id          varchar(128),
    event_type        varchar(64),
    topic             varchar(64),
    source_type       varchar(64),
    source_id         varchar(255),
    title             varchar(1024),
    summary           clob,
    url               varchar(1024),
    payload           clob,
    status            varchar(32) not null default 'PROCESSING',
    retry_count       int not null default 0,
    last_error        clob,
    idempotency_key   varchar(255) not null unique,
    created_at        timestamp with time zone not null default current_timestamp,
    processed_at      timestamp with time zone,
    updated_at        timestamp with time zone not null default current_timestamp
);

create table public.notifications (
    id              uuid primary key,
    event_audit_id  uuid,
    topic           varchar(64) not null,
    title           varchar(1024) not null,
    body            clob,
    url             varchar(1024),
    created_at      timestamp with time zone not null default current_timestamp,
    foreign key (event_audit_id) references public.content_event_audit(id) on delete set null
);

create table public.notification_recipients (
    id                uuid primary key,
    notification_id   uuid not null,
    subscriber_id     uuid not null,
    channel           varchar(16) not null,
    status            varchar(32) not null default 'PENDING',
    retry_count       int not null default 0,
    next_retry_at     timestamp with time zone,
    sent_at           timestamp with time zone,
    read_at           timestamp with time zone,
    last_error        clob,
    idempotency_key   varchar(255) not null unique,
    created_at        timestamp with time zone not null default current_timestamp,
    updated_at        timestamp with time zone not null default current_timestamp,
    foreign key (notification_id) references public.notifications(id) on delete cascade,
    foreign key (subscriber_id) references public.subscribers(id) on delete cascade
);
