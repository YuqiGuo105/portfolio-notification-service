# Portfolio Notification Service

Kafka-driven subscription & notification backend for the [yuqi.site](https://www.yuqi.site) portfolio.

Owns the **entire** subscription/notification backend:

| Surface | Responsibility |
|---|---|
| REST API | `/api/subscriptions`, `/api/subscriptions/preferences`, `/api/subscriptions/unsubscribe`, `/api/notifications`, `/api/notifications/{id}/read`, `/api/health/notification` |
| Kafka consumer | Reads `portfolio.content-events`, writes audit + fan-out rows; DLQs invalid events |
| Email worker | Scheduled scanner that sends EMAIL channel recipients via SMTP |
| Storage | Supabase Postgres (via JDBC) — Flyway-managed schema |

The Next.js Portfolio repo only ships frontend components (SubscribeDialog, NotificationBell, NotificationDropdown) and thin proxy API routes that forward to this service.

---

## Stack

- Java 21, Spring Boot 3.3.4
- Spring Web, Spring Kafka, Spring JDBC, Spring Mail, Spring Actuator
- Flyway (Postgres migrations)
- JUnit 5 + Spring Boot Test + H2 (PostgreSQL mode) for integration tests
- Maven (with committed Maven Wrapper)
- Docker (Eclipse Temurin 21 JRE runtime image, non-root)

---

## Quickstart (local)

### 1. Apply the schema to Supabase

Flyway will run automatically on service startup if your `SPRING_DATASOURCE_URL` points at Postgres. Alternatively, paste [`src/main/resources/db/migration/V1__initial_schema.sql`](src/main/resources/db/migration/V1__initial_schema.sql) into the Supabase SQL editor.

### 2. Configure env

```bash
cp .env.example .env
# Fill in: Supabase DB password, Aiven Kafka credentials, SMTP credentials, TOKEN_PEPPER
```

### 3. Run

```bash
./mvnw spring-boot:run
# or
./mvnw -DskipTests package && java -jar target/portfolio-notification-service.jar
```

Health check:

```bash
curl http://localhost:8080/api/health/notification
```

### 4. Run tests

```bash
./mvnw -Dspring.profiles.active=test test
```

22 tests cover token hashing, event validation, end-to-end fan-out, idempotency, DLQ, unsubscribe, and email backoff.

---

## API

### `POST /api/subscriptions`
```json
{
  "email": "visitor@example.com",
  "topics": ["ARTICLE_UPDATES", "FEATURE_UPDATES"],
  "channels": ["WEB", "EMAIL"]
}
```
Response:
```json
{
  "subscriberId": "uuid",
  "subscriberToken": "64-hex",
  "unsubscribeToken": "64-hex",
  "topics": ["ARTICLE_UPDATES","FEATURE_UPDATES"],
  "channels": ["WEB","EMAIL"]
}
```
Store `subscriberId` + `subscriberToken` in browser `localStorage`. Only **hashes** are persisted server-side.

### `PATCH /api/subscriptions/preferences`
```json
{
  "subscriberId": "uuid",
  "subscriberToken": "raw-token",
  "preferences": [
    { "topic": "ARTICLE_UPDATES", "emailEnabled": true, "webEnabled": true }
  ]
}
```

### `POST /api/subscriptions/unsubscribe`
```json
{ "token": "unsubscribe-token-from-email" }
```
Always 200 (does not leak token validity). When matched: sets subscriber to `UNSUBSCRIBED` and marks pending EMAIL recipients `SKIPPED`.

### `GET /api/notifications?subscriberId=...&subscriberToken=...&status=unread`
Returns recent **WEB** notifications for the subscriber plus `unreadCount`. With `status=unread`, only `PENDING`/`SENT` rows.

### `PATCH /api/notifications/{id}/read`
```json
{ "subscriberId": "uuid", "subscriberToken": "raw-token" }
```
Marks a WEB recipient row `READ`. Only updates rows where `subscriber_id` matches and `channel = 'WEB'`.

### `GET /api/health/notification`
Returns `200` with `{ status: "UP", details: { db, tables } }` when DB + required tables are present; `503` otherwise.

---

## Kafka

### Consumer
- Group: `portfolio-notification-consumer-group`
- Topic: `portfolio.content-events`
- Offset commit: manual; commits only after DB write succeeds (or after a DLQ publish for permanently invalid events).
- DLQ topic: `portfolio.dlq` (raw payload + reason header).

### Event contract (`portfolio.content-events`)

```json
{
  "eventId": "evt_01HXYZ",
  "eventType": "ARTICLE_PUBLISHED",
  "topic": "ARTICLE_UPDATES",
  "sourceType": "BLOG",
  "sourceId": "blog_123",
  "title": "New article: Kafka Notification System",
  "summary": "How I built a Kafka-powered notification system for my Portfolio.",
  "url": "/blog-single/blog_123",
  "createdAt": "2026-06-20T20:00:00Z",
  "idempotencyKey": "ARTICLE_PUBLISHED:blog_123:v1",
  "metadata": { "author": "Yuqi Guo", "site": "yuqi.site" }
}
```

Allowed `eventType`: `ARTICLE_PUBLISHED`, `ARTICLE_UPDATED`, `FEATURE_RELEASED`, `JOB_POSITION_UPDATED`.
Allowed `topic`: `ARTICLE_UPDATES`, `FEATURE_UPDATES`, `JOB_UPDATES`.

### Idempotency

`content_event_audit.idempotency_key` is unique. Each fan-out row uses `idempotencyKey = "{notificationId}:{subscriberId}:{WEB|EMAIL}"` so retried deliveries never double-notify a subscriber.

### Sending a test event

Use the helper script ([`scripts/send-test-event.sh`](scripts/send-test-event.sh)) — it shells out to `kafkacat`/`kcat`:

```bash
KAFKA_BROKERS=... KAFKA_USERNAME=... KAFKA_PASSWORD=... \
  bash scripts/send-test-event.sh
```

Or insert a row directly into `content_event_audit` to simulate a processed event for local UI testing (see [`scripts/insert-fake-notification.sh`](scripts/insert-fake-notification.sh)).

---

## Cloud Run deployment

### One-time GCP setup (run on your workstation)

```bash
# 1. Variables
export PROJECT_ID=your-gcp-project
export REGION=us-central1
export ARTIFACT_REPO=portfolio
export GH_REPO=YuqiGuo105/portfolio-notification-service

# 2. Enable APIs
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  secretmanager.googleapis.com \
  --project=$PROJECT_ID

# 3. Artifact Registry repo
gcloud artifacts repositories create $ARTIFACT_REPO \
  --repository-format=docker --location=$REGION --project=$PROJECT_ID

# 4. Service accounts
gcloud iam service-accounts create ci-deployer --project=$PROJECT_ID
gcloud iam service-accounts create notification-runtime --project=$PROJECT_ID

# 5. Grant deployer roles
for role in roles/run.admin roles/artifactregistry.writer roles/iam.serviceAccountUser; do
  gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:ci-deployer@$PROJECT_ID.iam.gserviceaccount.com" \
    --role=$role
done

# 6. Runtime service account needs Secret Manager accessor
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:notification-runtime@$PROJECT_ID.iam.gserviceaccount.com" \
  --role=roles/secretmanager.secretAccessor

# 7. Workload Identity Federation for GitHub Actions
gcloud iam workload-identity-pools create github \
  --location=global --project=$PROJECT_ID
gcloud iam workload-identity-pools providers create-oidc github-provider \
  --location=global --workload-identity-pool=github \
  --display-name="GitHub Actions" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --project=$PROJECT_ID

# Bind the deployer SA so $GH_REPO can impersonate it
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
gcloud iam service-accounts add-iam-policy-binding \
  ci-deployer@$PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/$GH_REPO" \
  --project=$PROJECT_ID

# 8. Create secrets (paste values when prompted)
for s in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
         KAFKA_BROKERS KAFKA_USERNAME KAFKA_PASSWORD \
         SMTP_HOST SMTP_USER SMTP_PASSWORD TOKEN_PEPPER; do
  gcloud secrets create "$s" --replication-policy=automatic --project=$PROJECT_ID || true
  echo "Now run: echo -n 'value' | gcloud secrets versions add $s --data-file=-"
done
```

### GitHub repo settings → Variables (Actions)

| Variable | Example |
|---|---|
| `GCP_PROJECT_ID` | `your-gcp-project` |
| `GCP_REGION` | `us-central1` |
| `ARTIFACT_REPO` | `portfolio` |
| `WIF_PROVIDER` | `projects/123456789/locations/global/workloadIdentityPools/github/providers/github-provider` |
| `DEPLOYER_SA_EMAIL` | `ci-deployer@your-gcp-project.iam.gserviceaccount.com` |
| `RUNTIME_SA_EMAIL` | `notification-runtime@your-gcp-project.iam.gserviceaccount.com` |
| `PORTFOLIO_BASE_URL` | `https://www.yuqi.site` |
| `ALLOWED_ORIGINS` | `https://www.yuqi.site,http://localhost:3000` |

### Trigger deploy

Push a tag:
```bash
git tag v0.1.0
git push origin v0.1.0
```
…or run **Actions → Deploy to Cloud Run → Run workflow** manually.

The job builds the JAR, pushes the Docker image to Artifact Registry, deploys to Cloud Run with the secrets attached, then `curl`s `/api/health/notification` as a smoke test.

---

## Aiven Kafka quick setup (free tier)

1. Create a free Kafka service in [Aiven Console](https://console.aiven.io/).
2. Create topics `portfolio.content-events` (3 partitions, retention 7d) and `portfolio.dlq` (1 partition, retention 30d) under **Topics**.
3. Under **Users**, create a SCRAM user; copy username + password.
4. Add brokers + credentials to the GCP secrets above (`KAFKA_BROKERS`, `KAFKA_USERNAME`, `KAFKA_PASSWORD`).
5. SSL is on by default; the service uses `SASL_SSL` with `SCRAM-SHA-256`.

---

## Security

See [SECURITY.md](SECURITY.md). Highlights:

- `SUPABASE_SERVICE_ROLE_KEY` is **never** used in this service. It only needs a normal Postgres connection.
- Raw `subscriberToken` and `unsubscribeToken` are never stored; only SHA-256(pepper || token) is persisted.
- Constant-time comparison on token verification.
- The Next.js anon key is the only Supabase credential exposed to the browser; it is used solely to subscribe to Realtime INSERT pings on `notification_recipients`. Actual notification content always flows through verified API calls.

**Action items for this repo (do these before going to prod):**

1. **Rotate the secrets that were pasted into chat history** during planning:
   - `SUPABASE_SERVICE_ROLE_KEY` (rotate in Supabase dashboard)
   - `EMAIL_PASS` (revoke + regenerate the Gmail app password)
   - `NEXT_PUBLIC_SUPABASE_ANON_KEY` (rotate if exposed beyond `.env.local`)
2. Set `TOKEN_PEPPER` to a fresh 32-char random string. Rotating it invalidates all existing subscriber tokens (subscribers must re-subscribe). Roll forward with a one-time DB script if you need a graceful rotation.
3. Verify `.env` is gitignored in **both** repos before committing.

---

## Repo layout

```
.
├── .github/workflows/
│   ├── ci.yml          # build, test, docker smoke
│   └── deploy.yml      # build → push → Cloud Run
├── .mvn/wrapper/       # Maven Wrapper (committed)
├── mvnw, mvnw.cmd      # Maven Wrapper launchers
├── Dockerfile          # Multi-stage Temurin 21 build
├── pom.xml
├── scripts/
│   ├── send-test-event.sh
│   └── insert-fake-notification.sh
├── src/main/java/site/yuqi/notifications/
│   ├── NotificationApplication.java
│   ├── config/         # CORS
│   ├── controller/     # SubscriptionController, NotificationController, HealthController
│   ├── domain/         # enums + records
│   ├── dto/            # request/response DTOs
│   ├── exception/      # 401/404/400 mappers
│   ├── kafka/          # ContentEventConsumer, DlqProducer
│   ├── repository/     # JdbcTemplate-based repos
│   ├── scheduler/      # EmailDispatchScheduler
│   └── service/        # TokenService, SubscriptionService, NotificationService,
│                       # ContentEventProcessor, EmailDispatchService
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__initial_schema.sql
└── src/test/...        # 22 tests
```
