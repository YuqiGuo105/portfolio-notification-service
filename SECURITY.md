# Security

## Threat model (brief)

| Asset | Threat | Mitigation |
|---|---|---|
| Subscriber email | leak via API or DB | RLS on Supabase tables; tokens hashed; no email exposed via anon Realtime |
| Subscriber identity | hijack (impersonation) | `subscriberToken` raw value lives only in the browser; server stores SHA-256(pepper \|\| token) and compares in constant time |
| Notification payload | unauthorized read | All GET/PATCH endpoints verify `subscriberId + subscriberToken` |
| Unsubscribe abuse | replay / enumeration | unsubscribe API always returns 200; tokens are 256-bit; hashes are indexed |
| SMTP creds / DB password / Kafka creds | leak via repo or logs | Pulled from Google Secret Manager at runtime; never committed; structured logs only emit metadata, not payload bodies |

## Token storage

- 256-bit random tokens (`SecureRandom`), encoded as 64 hex chars.
- `subscriber_token_hash` = `hex(SHA-256(pepper || token))`.
- `unsubscribe_token_hash` = same scheme.
- Pepper (`TOKEN_PEPPER`) is a Secret Manager secret, **not** a per-user salt.
- `MessageDigest.isEqual` used for comparison (constant-time).

## OWASP Top 10 mapping

| OWASP | Status |
|---|---|
| A01 Broken access control | All notification + preferences endpoints require valid `subscriberToken`. Mark-read query is double-scoped on `recipient_id AND subscriber_id`. |
| A02 Cryptographic failures | Tokens hashed (SHA-256 + pepper); raw tokens only in transit over TLS. |
| A03 Injection | All DB writes use `JdbcTemplate` with `?` placeholders. No string concatenation of user input into SQL. JSON parsing uses Jackson with `@JsonIgnoreProperties(ignoreUnknown=true)`. |
| A04 Insecure design | Idempotency keys + DLQ guard against poison-pill / replay. |
| A05 Misconfiguration | Actuator info/health only exposed; Tomcat default error suppressed by global handler. |
| A06 Vulnerable components | Spring Boot 3.3.4 LTS line; bump via `mvn versions:display-dependency-updates`. |
| A07 ID & auth failures | Tokens hashed at rest; rate-limit recommended at the Vercel edge (TODO). |
| A08 Software integrity | Maven Wrapper committed; CI uses pinned action versions (`@v4`, `@v2`). |
| A09 Logging | Structured single-line JSON logs; no PII (no email, no token, no payload body) is logged. |
| A10 SSRF | Service does not call arbitrary URLs. `url` field is used only as opaque text persisted to DB. |

## Secret rotation playbook

Run these one at a time after compromise:

1. **`TOKEN_PEPPER`** — rotating invalidates **all** subscriber tokens. To roll smoothly:
   ```sql
   update public.subscribers set status = 'ACTIVE' /* trigger re-subscribe flow */;
   ```
   Or do a hard rotation and accept that everyone has to re-subscribe.
2. **Supabase DB password** — rotate in Supabase dashboard → update GCP secret → redeploy.
3. **Aiven Kafka SCRAM password** — rotate in Aiven console → update GCP secret → redeploy.
4. **SMTP app password** — rotate in your provider → update GCP secret → redeploy.
5. **`SUPABASE_SERVICE_ROLE_KEY`** — this service does **not** use it; rotate in Supabase if it leaked elsewhere.

## Reporting

Open a private GitHub Security Advisory on this repository.
