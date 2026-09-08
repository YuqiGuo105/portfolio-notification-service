alter table notification_recipients drop constraint if exists notification_recipients_status_chk;
alter table notification_recipients add constraint notification_recipients_status_chk
    check (status in ('PENDING','SENDING','UNKNOWN','SENT','FAILED','READ','SKIPPED'));
