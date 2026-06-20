#!/usr/bin/env python3
"""Insert a fake notification + WEB recipient for a given subscriber.

Reads connection info from env:
  SPRING_DATASOURCE_URL  jdbc:postgresql://host:port/db?sslmode=require
  SPRING_DATASOURCE_USERNAME
  SPRING_DATASOURCE_PASSWORD

Args: subscriber_id  notification_id  recipient_id  topic
"""
import os
import re
import ssl
import sys
import urllib.parse

import pg8000.dbapi


def parse_jdbc(jdbc_url: str):
    if not jdbc_url.startswith("jdbc:postgresql://"):
        raise SystemExit(f"Unsupported JDBC URL: {jdbc_url}")
    raw = jdbc_url[len("jdbc:postgresql://") :]
    # split host[:port]/db?query
    if "/" not in raw:
        raise SystemExit(f"Missing database in JDBC URL: {jdbc_url}")
    hostport, rest = raw.split("/", 1)
    if "?" in rest:
        db, qs = rest.split("?", 1)
        params = dict(urllib.parse.parse_qsl(qs))
    else:
        db, params = rest, {}
    if ":" in hostport:
        host, port = hostport.rsplit(":", 1)
        port = int(port)
    else:
        host, port = hostport, 5432
    require_ssl = params.get("sslmode", "prefer") in ("require", "verify-ca", "verify-full")
    return host, port, db, require_ssl


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: insert_fake_notification.py SUB_ID NOTIF_ID RECIP_ID TOPIC")
    sub_id, notif_id, recip_id, topic = sys.argv[1:]
    host, port, db, want_ssl = parse_jdbc(os.environ["SPRING_DATASOURCE_URL"])
    user = os.environ["SPRING_DATASOURCE_USERNAME"]
    password = os.environ["SPRING_DATASOURCE_PASSWORD"]

    ssl_ctx = None
    if want_ssl:
        ssl_ctx = ssl.create_default_context()
        # Supabase certs are valid; keep default verification on.

    conn = pg8000.dbapi.connect(
        user=user, password=password, host=host, port=port, database=db, ssl_context=ssl_ctx
    )
    try:
        cur = conn.cursor()
        cur.execute(
            "insert into public.notifications (id, topic, title, body, url) "
            "values (%s, %s, %s, %s, %s)",
            (
                notif_id,
                topic,
                "E2E test notification",
                "Inserted by scripts/local-e2e.sh",
                "https://www.yuqi.site/blog-single/e2e_test",
            ),
        )
        cur.execute(
            "insert into public.notification_recipients "
            "(id, notification_id, subscriber_id, channel, status, idempotency_key) "
            "values (%s, %s, %s, 'WEB', 'PENDING', %s)",
            (recip_id, notif_id, sub_id, f"{notif_id}:{sub_id}:WEB"),
        )
        conn.commit()
        print(f"inserted notification={notif_id} recipient={recip_id}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
