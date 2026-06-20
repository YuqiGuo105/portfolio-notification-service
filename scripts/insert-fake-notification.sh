#!/usr/bin/env bash
# Insert a fake notification + recipient row directly into Supabase Postgres,
# bypassing Kafka. Useful for frontend UI testing without a running Kafka cluster.
#
# Usage:
#   SUPABASE_DB_URL='postgres://postgres:PASS@db.<ref>.supabase.co:5432/postgres?sslmode=require' \
#   SUBSCRIBER_ID='<uuid>' \
#   bash scripts/insert-fake-notification.sh
#
# Run `\\d public.subscribers` in psql first to find a SUBSCRIBER_ID — or call
# POST /api/subscriptions once and grab the returned subscriberId.

set -euo pipefail

: "${SUPABASE_DB_URL:?SUPABASE_DB_URL required}"
: "${SUBSCRIBER_ID:?SUBSCRIBER_ID required}"

NOTIF_ID=$(uuidgen | tr 'A-Z' 'a-z')
RECIPIENT_ID=$(uuidgen | tr 'A-Z' 'a-z')
SUFFIX=$(date +%s)

psql "${SUPABASE_DB_URL}" <<SQL
insert into public.notifications (id, topic, title, body, url)
values ('${NOTIF_ID}', 'ARTICLE_UPDATES',
        'Fake test notification ${SUFFIX}',
        'Inserted by insert-fake-notification.sh',
        'https://www.yuqi.site/blog-single/fake_${SUFFIX}');

insert into public.notification_recipients
  (id, notification_id, subscriber_id, channel, status, idempotency_key)
values
  ('${RECIPIENT_ID}', '${NOTIF_ID}', '${SUBSCRIBER_ID}', 'WEB', 'PENDING',
   '${NOTIF_ID}:${SUBSCRIBER_ID}:WEB');
SQL

echo "Inserted recipient ${RECIPIENT_ID} for subscriber ${SUBSCRIBER_ID}."
echo "If the frontend is subscribed to Supabase Realtime, the bell should refresh."
