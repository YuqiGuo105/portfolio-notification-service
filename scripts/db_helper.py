#!/usr/bin/env python3
"""Tiny helpers for local-e2e.sh — read columns from the DB without psql."""
import os
import ssl
import sys
import urllib.parse

import pg8000.dbapi


def parse_jdbc(jdbc_url: str):
    raw = jdbc_url[len("jdbc:postgresql://") :]
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


def _conn():
    host, port, db, want_ssl = parse_jdbc(os.environ["SPRING_DATASOURCE_URL"])
    ssl_ctx = ssl.create_default_context() if want_ssl else None
    return pg8000.dbapi.connect(
        user=os.environ["SPRING_DATASOURCE_USERNAME"],
        password=os.environ["SPRING_DATASOURCE_PASSWORD"],
        host=host,
        port=port,
        database=db,
        ssl_context=ssl_ctx,
    )


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: db_helper.py {get-sub-status SUB_ID | cleanup SUB_ID NOTIF_ID}")
    cmd = sys.argv[1]
    conn = _conn()
    try:
        cur = conn.cursor()
        if cmd == "get-sub-status":
            cur.execute("select status from public.subscribers where id=%s", (sys.argv[2],))
            row = cur.fetchone()
            print(row[0] if row else "")
        elif cmd == "cleanup":
            sub_id, notif_id = sys.argv[2], sys.argv[3]
            cur.execute("delete from public.notification_recipients where subscriber_id=%s", (sub_id,))
            cur.execute("delete from public.notifications where id=%s", (notif_id,))
            cur.execute("delete from public.subscription_preferences where subscriber_id=%s", (sub_id,))
            cur.execute("delete from public.subscribers where id=%s", (sub_id,))
            conn.commit()
            print("cleaned up")
        else:
            raise SystemExit(f"unknown cmd: {cmd}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
