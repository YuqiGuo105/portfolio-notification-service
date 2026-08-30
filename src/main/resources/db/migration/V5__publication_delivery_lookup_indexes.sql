create index if not exists content_event_audit_source_idx
    on public.content_event_audit(source_id, created_at desc);

create index if not exists content_event_audit_event_idx
    on public.content_event_audit(event_id);
