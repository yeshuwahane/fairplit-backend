# FairSplit Server — Microservice Architecture & API Reference

> High-performance asynchronous backend service for FairSplit expense sharing platform built on **Ktor 3.0**, **Exposed ORM**, **Flyway**, and **PostgreSQL**.

---

## Architecture & Design Highlights

- **Ktor 3.0 Netty Engine**: Fully non-blocking coroutine-driven server architecture.
- **Exposed ORM + HikariCP**: Type-safe relational database access with tuned connection pooling.
- **Server-Authoritative Accounting**: Integer minor currency units (`amount_minor BIGINT`), deterministic remainder distribution, and bilateral debt computation.
- **Post-Commit Realtime Broadcaster**: WebSocket pub/sub rooms isolated by Epic UUID (`/api/v1/ws/epics/{epicId}`).
- **Idempotency Protection**: Deterministic hash comparison on mutations prevents double writes under high latency.
- **Token Security**: HMAC-SHA256 JWT access tokens paired with SHA-256 hashed refresh tokens and atomic session rotation.

---

## Configuration Reference

All settings can be configured via environment variables or `application.conf`:

| Environment Variable | Description | Default |
|---|---|---|
| `PORT` | Listening port for Netty HTTP server | `8080` |
| `HOST` | Network interface binding | `0.0.0.0` |
| `APP_ENV` | Application environment (`dev` or `prod`) | `dev` |
| `DATABASE_URL` | JDBC connection string | `jdbc:h2:./data/fairsplit_dev` (fallback) |
| `DATABASE_USER` | PostgreSQL database user | `""` |
| `DATABASE_PASSWORD` | PostgreSQL database password | `""` |
| `DATABASE_MAX_POOL_SIZE` | HikariCP pool maximum size | `10` |
| `JWT_SECRET` | Secret key for signing access tokens (>= 32 chars) | Dev dummy key |
| `JWT_ACCESS_TTL_DAYS` | Access token lifespan in days | `30` |
| `JWT_REFRESH_TTL_DAYS`| Refresh token lifespan in days | `90` |
| `UPLOAD_DIRECTORY` | Local storage directory for user image uploads | `uploads` |

---

## API Summary

### 1. Authentication (`/api/v1/auth`)
- `POST /phone/request-otp` — Generate OTP challenge (dev: `123456`, prod: random 6 digits)
- `POST /phone/verify-otp` — Verify OTP challenge & issue JWT token pair
- `POST /google` — Authenticate Google user
- `POST /refresh` — Atomically rotate refresh token & issue new token pair
- `POST /logout` — Revoke active refresh session
- `GET /me` — Check authenticated identity

### 2. Group & Sync Operations (`/api/v1/sync`)
- `GET /epics` — List user's active groups
- `POST /epics` — Create group
- `GET /epics/{id}` — Get group detail with live net balances and debts
- `PUT /epics/{id}` — Update group details
- `DELETE /epics/{id}` — Delete group (owner only)
- `POST /epics/join` — Join group via invite code
- `GET /epics/{id}/expenses` — List group expenses
- `POST /epics/{id}/expenses` — Create expense (supports `Idempotency-Key`)
- `DELETE /epics/{id}/expenses/{expenseId}` — Delete expense
- `GET /transactions` — List settlement transactions
- `POST /transactions` — Create settlement (validates against bilateral debt)
- `PUT /transactions/{id}` — Edit settlement amount
- `DELETE /transactions/{id}` — Void settlement (preserves audit record)
- `GET /activities` — Audit log of group actions
- `POST /upload` — Upload receipt/profile image (max 15MB)

### 3. Realtime WebSockets (`/api/v1/ws`)
- `GET /ws/epics/{epicId}?token=<JWT>` — Connect to Epic room

### 4. Health Check
- `GET /health` — Application health check (returns HTTP 200 `{"status": "ok"}`)

---

## Building & Testing

```bash
# Clean and run all unit & integration tests
./gradlew clean test

# Build application distribution
./gradlew installDist

# Run locally
./gradlew run
```

---

## Container Deployment

```bash
docker build -t fairsplit-server:latest .
docker run -p 8080:8080 fairsplit-server:latest
```
