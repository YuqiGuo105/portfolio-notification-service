#!/usr/bin/env bash
# Local end-to-end test against the user's real Supabase Postgres.
# Kafka is disabled; we exercise subscribe → fake-notification → list → mark-read → unsubscribe.
#
# Required env (export BEFORE running this script):
#   SPRING_DATASOURCE_URL      jdbc:postgresql://db.<ref>.supabase.co:5432/postgres?sslmode=require
#   SPRING_DATASOURCE_USERNAME postgres
#   SPRING_DATASOURCE_PASSWORD <your supabase db password>
#
# Optional:
#   TOKEN_PEPPER  (default: local-dev-pepper)
#   PORT          (default: 8088)

set -euo pipefail

: "${SPRING_DATASOURCE_URL:?Export SPRING_DATASOURCE_URL first}"
: "${SPRING_DATASOURCE_USERNAME:?Export SPRING_DATASOURCE_USERNAME first}"
: "${SPRING_DATASOURCE_PASSWORD:?Export SPRING_DATASOURCE_PASSWORD first}"

export TOKEN_PEPPER="${TOKEN_PEPPER:-local-dev-pepper}"
export PORTFOLIO_KAFKA_CONSUMER_ENABLED="false"
export PORT="${PORT:-8088}"
export SERVER_PORT="${PORT}"
export SMTP_HOST="${SMTP_HOST:-smtp.example.test}"
export SMTP_USER="${SMTP_USER:-test}"
export SMTP_PASSWORD="${SMTP_PASSWORD:-test}"
export KAFKA_BROKERS="${KAFKA_BROKERS:-localhost:9092}"
export KAFKA_SECURITY_PROTOCOL="${KAFKA_SECURITY_PROTOCOL:-PLAINTEXT}"
export KAFKA_SASL_MECHANISM="${KAFKA_SASL_MECHANISM:-}"
export KAFKA_USERNAME="${KAFKA_USERNAME:-}"
export KAFKA_PASSWORD="${KAFKA_PASSWORD:-}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

LOG=/tmp/notification-service-local.log
PID_FILE=/tmp/notification-service-local.pid

cleanup() {
  if [[ -f "$PID_FILE" ]]; then
    local pid; pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> Stopping notification service (pid=$pid)"
      kill "$pid" || true
      for _ in $(seq 1 10); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}
trap cleanup EXIT

echo "==> 1. Build (skip tests)"
./mvnw -B -ntp -DskipTests package -q

JAR="$(ls target/portfolio-notification-service-*.jar 2>/dev/null | head -n1 || true)"
if [[ -z "$JAR" ]]; then JAR="target/portfolio-notification-service.jar"; fi
echo "    jar = $JAR"

echo "==> 2. Start service on :$PORT (logs → $LOG)"
nohup java \
  -Dspring.profiles.active=default \
  -Dportfolio.kafka.consumer-enabled=false \
  -Dserver.port="$PORT" \
  -jar "$JAR" > "$LOG" 2>&1 &
echo $! > "$PID_FILE"

echo "==> 3. Wait for /api/health/notification (Flyway runs on first connect)"
HEALTH_URL="http://localhost:${PORT}/api/health/notification"
for i in $(seq 1 90); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    echo "    service is UP after ${i}s"
    break
  fi
  if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "ERROR: service process exited prematurely. Last log lines:"
    tail -120 "$LOG"
    exit 1
  fi
  sleep 1
done
if ! curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
  echo "ERROR: service did not come up in 90s. Log tail:"
  tail -120 "$LOG"
  exit 1
fi

echo
echo "==> 4. Health check"
curl -fsS "$HEALTH_URL" | python3 -m json.tool

EMAIL="e2e+$(date +%s)@example.test"

echo
echo "==> 5. POST /api/subscriptions  ($EMAIL)"
SUB_RESP=$(curl -fsS -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"topics\":[\"ARTICLE_UPDATES\",\"FEATURE_UPDATES\"],\"channels\":[\"WEB\",\"EMAIL\"]}" \
  "http://localhost:${PORT}/api/subscriptions")
echo "$SUB_RESP" | python3 -m json.tool

SUB_ID=$(echo "$SUB_RESP"      | python3 -c 'import sys,json; print(json.load(sys.stdin)["subscriberId"])')
SUB_TOKEN=$(echo "$SUB_RESP"   | python3 -c 'import sys,json; print(json.load(sys.stdin)["subscriberToken"])')
UNSUB_TOKEN=$(echo "$SUB_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["unsubscribeToken"])')
echo "    subscriberId: $SUB_ID"

echo
echo "==> 6. GET /api/notifications (should be empty)"
curl -fsS "http://localhost:${PORT}/api/notifications?subscriberId=${SUB_ID}&subscriberToken=${SUB_TOKEN}" \
  | python3 -m json.tool

echo
echo "==> 7. Verify unauthorized request returns 401"
HTTP=$(curl -s -o /tmp/unauth.json -w '%{http_code}' \
  "http://localhost:${PORT}/api/notifications?subscriberId=${SUB_ID}&subscriberToken=wrong")
if [[ "$HTTP" == "401" ]]; then
  echo "    ✓ 401 returned for bad token"
else
  echo "    ✗ expected 401, got $HTTP"
  cat /tmp/unauth.json
  exit 1
fi

NOTIF_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
RECIP_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"

echo
echo "==> 8. Insert fake notification + recipient via pg8000"
python3 scripts/insert_fake_notification.py "$SUB_ID" "$NOTIF_ID" "$RECIP_ID" "ARTICLE_UPDATES"

echo
echo "==> 9. GET /api/notifications (should now show 1 unread)"
LIST_RESP=$(curl -fsS "http://localhost:${PORT}/api/notifications?subscriberId=${SUB_ID}&subscriberToken=${SUB_TOKEN}")
echo "$LIST_RESP" | python3 -m json.tool
UNREAD=$(echo "$LIST_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["unreadCount"])')
if [[ "$UNREAD" != "1" ]]; then
  echo "    ✗ expected unreadCount=1, got ${UNREAD}"; exit 1
fi
echo "    ✓ unreadCount=1"

echo
echo "==> 10. PATCH /api/notifications/${RECIP_ID}/read"
curl -fsS -X PATCH -H "Content-Type: application/json" \
  -d "{\"subscriberId\":\"${SUB_ID}\",\"subscriberToken\":\"${SUB_TOKEN}\"}" \
  "http://localhost:${PORT}/api/notifications/${RECIP_ID}/read" \
  | python3 -m json.tool

echo
echo "==> 11. GET /api/notifications?status=unread (should be empty)"
LIST_RESP=$(curl -fsS "http://localhost:${PORT}/api/notifications?subscriberId=${SUB_ID}&subscriberToken=${SUB_TOKEN}&status=unread")
echo "$LIST_RESP" | python3 -m json.tool
UNREAD=$(echo "$LIST_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["unreadCount"])')
if [[ "$UNREAD" != "0" ]]; then
  echo "    ✗ expected unreadCount=0 after mark-read, got ${UNREAD}"; exit 1
fi
echo "    ✓ unreadCount=0"

echo
echo "==> 12. POST /api/subscriptions/unsubscribe"
curl -fsS -X POST -H "Content-Type: application/json" \
  -d "{\"token\":\"${UNSUB_TOKEN}\"}" \
  "http://localhost:${PORT}/api/subscriptions/unsubscribe" \
  | python3 -m json.tool

STATUS=$(python3 scripts/db_helper.py get-sub-status "$SUB_ID")
if [[ "$STATUS" != "UNSUBSCRIBED" ]]; then
  echo "    ✗ expected subscriber status UNSUBSCRIBED, got '${STATUS}'"; exit 1
fi
echo "    ✓ subscriber status is UNSUBSCRIBED"

echo
echo "==> 13. Cleanup test rows"
python3 scripts/db_helper.py cleanup "$SUB_ID" "$NOTIF_ID"

echo
echo "==> ALL CHECKS PASSED ✓"
echo "Service log: $LOG"
