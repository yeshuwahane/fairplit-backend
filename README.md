# FairSplit Server — Microservice Architecture & API Reference

> High-performance asynchronous backend service for the **FairSplit** financial settlement and expense-sharing platform built on **Ktor 3.0**, **Exposed ORM**, **Flyway**, and **PostgreSQL**.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-3.0.3-087CFA?logo=ktor&logoColor=white)](https://ktor.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![Railway](https://img.shields.io/badge/Deployed-Railway-0B0D0E?logo=railway&logoColor=white)](https://railway.app/)

---

## Architecture & Design Highlights

- **Ktor 3.0 Netty Engine**: High-concurrency, coroutine-based non-blocking server architecture.
- **Server-Authoritative Ledger**: Eliminates IEEE-754 floating-point inaccuracies by storing all financial values as integer minor currency units (`amount_minor BIGINT`).
- **Graph-Based Debt Simplification**: Reduces tangled pairwise debts ($O(A \rightarrow B)$) to the minimum number of transactions using bilateral graph algorithms.
- **Real-Time WebSockets**: Pub/sub rooms per group/epic (`/api/v1/ws/epics/{epicId}`) broadcast state mutations immediately to all active connected clients.
- **Idempotency Engine**: Deterministic SHA-256 payload validation with `Idempotency-Key` headers prevents duplicate charges and race conditions across mobile retries.
- **Secure Authentication**: Google Sign-In with HMAC-SHA256 JWT access tokens, SHA-256 hashed refresh tokens, and atomic session rotation.
- **Automated Database Migrations**: Flyway migration management ensures seamless forward schema evolution (`V1` through `V10`).

---

## Deployment & Production Hosting

- **Hosting Provider**: Railway
- **Production Base URL**: `https://fairplit-backend-production.up.railway.app`
- **WebSocket Gateway**: `wss://fairplit-backend-production.up.railway.app`
- **Health Check**: `GET https://fairplit-backend-production.up.railway.app/health`

---

## Configuration Reference

All settings can be configured via environment variables or `application.conf`:

| Environment Variable | Description | Default / Production |
|---|---|---|
| `PORT` | Listening port for Netty HTTP server | `8080` |
| `HOST` | Network interface binding | `0.0.0.0` |
| `APP_ENV` | Application environment (`dev` or `prod`) | `prod` on Railway |
| `DATABASE_URL` | PostgreSQL JDBC connection URL | Provided by Railway PostgreSQL |
| `DATABASE_USER` | PostgreSQL database user | Provided by Railway |
| `DATABASE_PASSWORD` | PostgreSQL database password | Provided by Railway |
| `DATABASE_MAX_POOL_SIZE` | HikariCP pool maximum size | `10` |
| `JWT_SECRET` | Secret key for signing access tokens (>= 32 chars) | Managed via environment variables |
| `JWT_ACCESS_TTL_DAYS` | Access token lifespan in days | `30` |
| `JWT_REFRESH_TTL_DAYS`| Refresh token lifespan in days | `90` |
| `UPLOAD_DIRECTORY` | Local storage directory for user image uploads | `uploads` |

---

## API Reference

### 1. Authentication (`/api/v1/auth`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/auth/google` | Authenticate Google account & issue JWT token pair |
| `POST` | `/api/v1/auth/refresh` | Atomically rotate refresh token & issue new token pair |
| `POST` | `/api/v1/auth/logout` | Revoke active refresh session |
| `GET` | `/api/v1/auth/me` | Retrieve profile of currently authenticated user |
| `POST` | `/api/v1/auth/phone/*` | *Deprecated (`410 Gone`)* |

### 2. Group & Sync Operations (`/api/v1/sync`)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/sync/epics` | List all Epics (groups) the user is a member of |
| `POST` | `/api/v1/sync/epics` | Create a new Epic |
| `GET` | `/api/v1/sync/epics/{id}` | Get Epic details with live net balances and debts |
| `PUT` | `/api/v1/sync/epics/{id}` | Update Epic details |
| `DELETE`| `/api/v1/sync/epics/{id}` | Delete Epic (owner only) |
| `POST` | `/api/v1/sync/epics/join` | Join Epic via 6-character invite code |
| `GET` | `/api/v1/sync/epics/{id}/expenses` | List all expenses for an Epic |
| `POST` | `/api/v1/sync/epics/{id}/expenses` | Create an expense (supports `Idempotency-Key`) |
| `DELETE`| `/api/v1/sync/epics/{id}/expenses/{id}` | Delete an expense |
| `GET` | `/api/v1/sync/transactions` | List settlement transactions |
| `POST` | `/api/v1/sync/transactions` | Record a settlement payment (validates against debt) |
| `PUT` | `/api/v1/sync/transactions/{id}` | Edit settlement amount |
| `DELETE`| `/api/v1/sync/transactions/{id}` | Void settlement (preserves audit log) |
| `GET` | `/api/v1/sync/activities` | Audit trail of group actions |
| `POST` | `/api/v1/sync/upload` | Upload bill receipt / avatar photo (max 15MB) |

### 3. Realtime WebSockets (`/api/v1/ws`)

| Protocol | Endpoint | Description |
|---|---|---|
| `WSS` | `/api/v1/ws/epics/{epicId}?token=<JWT>` | Realtime duplex stream for Epic updates |

### 4. Health Check

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/health` | Service health status (returns `{"status":"ok"}`) |

---

## Local Development & Testing

```bash
# Clean and run all unit & integration tests
./gradlew test

# Start local server (defaults to port 8080)
./gradlew run
```

---

## Container Deployment

A multi-stage `Dockerfile` is provided for zero-dependency container deployment:

```bash
# Build Docker image
docker build -t fairsplit-backend:latest .

# Run container locally
docker run -p 8080:8080 fairsplit-backend:latest
```

---

## License

Copyright © 2026 Yeshu Wahane. All rights reserved.
