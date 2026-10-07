# Browser Web Push

Opt-in device delivery for existing `WEB` preferences. Email and inbox behavior
remain independent.

## Flow

1. Subscriber ID/token authorizes the browser's device registration.
2. A public publication event creates inbox recipients and per-device push jobs
   in the same transaction. Administrative/suppressed events never enter push.
3. The dispatcher claims jobs, rechecks active subscriber/device and topic
   preference, then encrypts the public preview using the Web Push library.
4. The provider wakes the Service Worker, including when the page is closed.
   Display depends on browser permission and OS notification settings.

## Rollout

- Apply Flyway `V8__browser_push.sql` through the normal service rollout. Its two
  tables have RLS enabled and no anonymous/authenticated client grants.
- Configure a stable VAPID P-256 key pair as `WEB_PUSH_PUBLIC_KEY` and
  `WEB_PUSH_PRIVATE_KEY`, plus `WEB_PUSH_SUBJECT` (`mailto:` contact or HTTPS URL).
  The private key belongs in backend secret configuration, never browser code.
- Missing keys disable dispatch and return `enabled: false` to the frontend.
  Code/migrations can be released in this disabled state. Creating secrets and
  granting their runtime access are a separate approved activation step. Bind
  the approved key versions to the two Cloud Run environment variables; the
  deployment workflow uses `--update-secrets`, preserving additional bindings.
- The existing `portfolio-notification-recovery` Cloud Scheduler job calls
  `/api/internal/workers/drain` every five minutes. This request-scoped worker now
  drains push as well as email, one bounded provider call per pass. It wakes a
  scale-to-zero service without a browser being open and requires no new always-on
  instance. The in-process 30-second schedule also runs while CPU is available.
  Delivery may wait for the next recovery tick or subsequent ticks under backlog.
- Deploy frontend proxy, manifest, Service Worker and permission controls. Each
  device must explicitly enable notifications; old inbox subscriptions are not
  silently upgraded to OS permissions.
- Verify one authorized device with one targeted test preview. Never broadcast
  test publications to subscribers. Mock-based tests do not prove real-provider
  delivery.

This change does not apply migrations, configure cloud secrets, grant browser
permission or send production notifications automatically.

## API

All routes require the internal service token. Private operations also verify
subscriber ID/token and ownership.

| Route | Purpose |
| --- | --- |
| `GET /api/push/config` | Enabled flag and public VAPID key |
| `POST /api/push/subscriptions` | Nested `subscription: {endpoint, keys: {p256dh, auth}}` |
| `POST /api/push/status` | Check this subscriber's device registration |
| `DELETE /api/push/subscriptions` | Remove only this subscriber's device |

Registration requires a WEB-enabled preference and is limited to five devices per
subscriber. Encryption keys are validated. Only HTTPS Chrome/Firefox/Apple push
destinations are allowlisted; redirects are disabled. Endpoints are capability
URLs and must not be logged. Other providers need explicit compatibility testing.

## Delivery semantics

`PENDING -> SENDING -> SENT | RETRY | FAILED | SKIPPED | UNKNOWN`

- Unique device/notification keys prevent repeated publication fan-out.
- Atomic claims and claim tokens fence concurrent worker completions.
- 2xx means provider acceptance, not a delivery/read receipt.
- 404/410 disables expired devices. Visitors can explicitly re-enable them.
- 429/5xx use bounded backoff, up to five attempts. Provider duplicates remain
  possible; notification tags let the browser replace the same notification.
- Timeouts/expired leases become `UNKNOWN`, with no automatic resend because
  acceptance may already have occurred. Investigate before manual action.
- Unsubscribe, disabled WEB preferences and removed devices stop pending work.
- History retention, stale devices and VAPID rotation need operational planning.
  No automatic retention policy is added here.

## Tests

`./mvnw test` includes Web Push tests. Test configuration uses H2; the flow test
asserts H2 before clearing fixtures. Coverage includes authentication, ownership,
device limits, preferences, private-event suppression, deduplication, expiry,
retries and crash uncertainty. The transport test performs actual VAPID signing
and encryption without network delivery.

The frontend browser test closes the website and injects a local push event into
a real Chrome Service Worker. Registration/provider HTTP calls remain mocked;
real-provider delivery is a separate release check.
