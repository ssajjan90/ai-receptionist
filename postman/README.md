# Testing ai-receptionist with Postman

## 1. Import

1. Postman → **Import** → select both:
   - `postman/AI-Receptionist.postman_collection.json`
   - `postman/AI-Receptionist-Local.postman_environment.json`
2. Top-right environment dropdown → select **AI Receptionist - Local**.
3. Make sure the app stack is running: `docker compose up -d` from the repo root, then confirm with the **0 - Health** request (expect `status: UP`, port 8080).

## 2. Walk through the folders in order

### 1 - Tenant Onboarding
1. **1.1 Register Tenant** — creates the tenant, auto-saves `tenantId`. It also logs a 6-digit OTP (no real WhatsApp send locally):
   ```
   docker logs ai-receptionist-app-1 --since 1m | grep "Sending OTP"
   ```
   Copy that code into the environment's `otp` variable.
2. **1.2 Verify OTP** — this is the login step; it mints the JWT and auto-saves it as `ownerJwt`. OTP expires in 5 minutes — if you're slow, re-run **1.3 Resend OTP** and grab the new code from the logs.
3. **1.4 Complete Onboarding** — seeds two knowledge-base entries you can see in folder 3.

### 2 - WhatsApp
- **2.1 Connect WhatsApp Number** — required before webhook messages will route correctly.
- **2.2 / 2.3** simulate inbound messages via the Twilio-shaped local webhook (`/webhooks/whatsapp/twilio`) — no signature needed, unlike the real Meta webhook. 2.2 is a customer asking about a product (routes through the AI pipeline — needs a real OpenAI key in your env to produce a real answer; without one, expect a fallback/error, which is still fine for testing routing). 2.3 is the owner sending a KB command (`ADD FAQ: ...`).

### 3 - Knowledge Base
Standard CRUD, all verified working. `type` must be a valid `EntryType` enum value (`PRODUCT`, `FAQ`, etc.) — check `com.aireceptionist.knowledgebase.domain.EntryType` if you add other types and get a 400. The OCR endpoints (3.5/3.6) need real OCR provider credentials to do anything beyond validation — the shapes are correct, but expect them to fail locally without a configured key.

### 4 - Leads
Leads only appear once the AI pipeline detects purchase intent from a customer message (folder 2) and actually completes — this needs a real LLM key configured. If **4.1 List Leads** comes back empty, that's expected without one; you can still exercise 4.2–4.5 manually once a lead exists.

### 5 - Admin (Story 5-1)
No login endpoint mints a `PLATFORM_ADMIN` token — there isn't one yet (out of scope for Story 5-1). To get one for testing:
```bash
cd /Users/macbookair/Desktop/Suresh/ai-receptionist
node scripts/mint-admin-jwt.js
```
This signs a token locally using the exact RSA key `application-local.yml` gives the running app (pure Node `crypto`, no install needed). Paste the output into the `adminJwt` environment variable, then run 5.1/5.2.

## 3. Known gaps (not bugs, just not built yet)
- No `/login` endpoint for returning owners — `verify-otp` is the only way to get a JWT, and it always registers-then-verifies.
- No admin login/registration endpoint — use `mint-admin-jwt.js` for now.
- Voice, Billing, and Franchise modules (Epics 6-8) have no controllers yet — nothing to test there.

## 4. If you hit a 403/500 everywhere
Two real bugs were found and fixed while building this collection (2026-07-16), both in shared infrastructure, not tenant-specific:
1. **Docker Desktop ↔ Testcontainers version mismatch** — bumped `testcontainers.version` to `1.21.4` in `pom.xml`.
2. **`docker-compose.yml` port mismatch** — `application-local.yml` sets `server.port: 8081`; compose now maps `8080:8081`.
3. **`TenantConnectionProvider` used `SET x = ?`** (invalid Postgres syntax for a prepared statement) instead of `set_config()`, which 500'd *every* tenant-scoped JPA endpoint. Fixed in `src/main/java/com/aireceptionist/common/multitenancy/TenantConnectionProvider.java`.

If you're on a fresh checkout and things are broken again, rebuild: `mvn package -DskipTests && docker compose build app && docker compose up -d app`.

There's also a known, unresolved issue: migrations V19/V21 use `CREATE INDEX CONCURRENTLY`, which deadlocks against Flyway's own advisory-lock connection on a fresh database (reproduced twice, deterministically). It only bites on a *fresh* database (already resolved on your current local DB) — but will hit again on any new environment (new teammate, CI, prod). Recommended permanent fix: drop `CONCURRENTLY` from those two migrations.
