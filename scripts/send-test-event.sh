#!/usr/bin/env bash
# Send a sample ARTICLE_PUBLISHED event to portfolio.content-events.
#
# Requires `kcat` (or kafkacat). Install:
#   brew install kcat            # macOS
#   apt-get install kafkacat     # Debian/Ubuntu
#
# Usage:
#   KAFKA_BROKERS=kafka-xxx.aivencloud.com:12345 \
#   KAFKA_USERNAME=avnadmin \
#   KAFKA_PASSWORD=*** \
#   bash scripts/send-test-event.sh [--bad]

set -euo pipefail

: "${KAFKA_BROKERS:?KAFKA_BROKERS required}"
: "${KAFKA_USERNAME:?KAFKA_USERNAME required}"
: "${KAFKA_PASSWORD:?KAFKA_PASSWORD required}"

TOPIC="${KAFKA_TOPIC_CONTENT_EVENTS:-portfolio.content-events}"
MECHANISM="${KAFKA_SASL_MECHANISM:-SCRAM-SHA-256}"

SUFFIX="$(date +%s)"

if [[ "${1:-}" == "--bad" ]]; then
  PAYLOAD='{"eventType":"NOT_REAL","topic":"OTHER","title":""}'
  KEY="invalid-${SUFFIX}"
  echo "Sending intentionally invalid event (should land in portfolio.dlq)..."
else
  PAYLOAD=$(cat <<JSON
{
  "eventId": "evt_${SUFFIX}",
  "eventType": "ARTICLE_PUBLISHED",
  "topic": "ARTICLE_UPDATES",
  "sourceType": "BLOG",
  "sourceId": "blog_${SUFFIX}",
  "title": "Test article ${SUFFIX}",
  "summary": "This is a synthetic event produced by send-test-event.sh",
  "url": "/blog-single/blog_${SUFFIX}",
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "idempotencyKey": "ARTICLE_PUBLISHED:blog_${SUFFIX}:v1",
  "metadata": { "source": "send-test-event.sh" }
}
JSON
  )
  KEY="blog_${SUFFIX}"
  echo "Sending ARTICLE_PUBLISHED event to ${TOPIC}..."
fi

echo "${PAYLOAD}" | kcat -P \
  -b "${KAFKA_BROKERS}" \
  -t "${TOPIC}" \
  -k "${KEY}" \
  -X security.protocol=SASL_SSL \
  -X sasl.mechanism="${MECHANISM}" \
  -X sasl.username="${KAFKA_USERNAME}" \
  -X sasl.password="${KAFKA_PASSWORD}"

echo "Sent."
echo "Watch consumer logs:"
echo "  gcloud run services logs read portfolio-notification-service --region=\$GCP_REGION --tail=50"
echo "Or check Supabase:"
echo "  select * from public.content_event_audit order by created_at desc limit 5;"
echo "  select * from public.notification_recipients order by created_at desc limit 5;"
