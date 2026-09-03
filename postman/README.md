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

Also see **1.5 Export My Data** / **1.6 Erase My Account** (DPDP, story 5.5) — 1.6 is destructive and immediate, only run it against a throwaway test tenant.

### 4 - Leads
Leads only appear once the AI pipeline detects purchase intent from a customer message (folder 2) and actually completes — this needs a real LLM key configured. If **4.1 List Leads** comes back empty, that's expected without one; you can still exercise 4.2–4.5 manually once a lead exists.

### 5 - Admin (Epic 5, stories 5.1-5.6)
No login endpoint mints a `PLATFORM_ADMIN` token — there isn't one yet, and `admin_users` has zero rows in every environment. To get one for testing:
```bash
cd /Users/macbookair/Desktop/Suresh/ai-receptionist
node scripts/mint-admin-jwt.js
```
This signs a token locally using the exact RSA key `application-local.yml` gives the running app (pure Node `crypto`, no install needed). Paste the output into the `adminJwt` environment variable, then run 5.1 onward.

Covers: dashboard listing/detail (5.1-5.2), admin data export (5.3), conversation log viewer (5.4), suspend/reactivate/terminate (5.5-5.7), notify/broadcast (5.8-5.9), and the audit log (5.10). **5.10 uses keyset pagination, not page numbers** — its test script auto-saves `nextCursorOccurredAt`/`nextCursorId` from the response into the `auditCursorOccurredAt`/`auditCursorId` environment variables when `hasMore: true`; just re-run 5.10 to fetch the next page. 5.7 Terminate schedules erasure 30 days out (not immediate, unlike 1.6) — safe to run against a test tenant.

### 6 - Voice (Epic 6, Story 6-1)
`POST /webhooks/voice` — the Exotel telephony webhook. `permitAll` but signature-verified, unlike the Twilio WhatsApp sandbox (folder 2). 6.1's pre-request script computes the `X-Exotel-Signature` header automatically (HMAC-SHA1 over `CallSid+From+To+Direction+Status`, keyed with the `exotelSharedSecret` environment variable — defaults to `test-exotel-shared-secret`, matching `application.yml`'s local default). Returns raw ExoML (XML), not the JSON envelope.

New tenants default to `tier=BASIC`, so expect a `<Play>...WhatsApp...</Play>` redirect. There's no tier-upgrade endpoint yet (Epic 7 billing isn't built) — to see the PRO/ENTERPRISE `<Record>` AI-handoff path, promote the tenant directly first:
```sql
UPDATE tenants SET tier='PRO' WHERE id='<tenantId>';
```

## 3. Known gaps (not bugs, just not built yet)
- No `/login` endpoint for returning owners — `verify-otp` is the only way to get a JWT, and it always registers-then-verifies.
- No admin login/registration endpoint — use `mint-admin-jwt.js` for now.
- No tier-upgrade endpoint — see folder 6's note above.
- Franchise/multi-location modules (Epic 8) have no controllers yet — nothing to test there.

See `docs/API_TESTING_GUIDE.md` for the full written reference (every endpoint with curl equivalents, response envelope shape, troubleshooting).

## 4. If you hit a 403/500 everywhere
Two real bugs were found and fixed while building this collection (2026-07-16), both in shared infrastructure, not tenant-specific:
1. **Docker Desktop ↔ Testcontainers version mismatch** — bumped `testcontainers.version` to `1.21.4` in `pom.xml`.
2. **`docker-compose.yml` port mismatch** — `application-local.yml` sets `server.port: 8081`; compose now maps `8080:8081`.
3. **`TenantConnectionProvider` used `SET x = ?`** (invalid Postgres syntax for a prepared statement) instead of `set_config()`, which 500'd *every* tenant-scoped JPA endpoint. Fixed in `src/main/java/com/aireceptionist/common/multitenancy/TenantConnectionProvider.java`.

If you're on a fresh checkout and things are broken again, rebuild: `mvn package -DskipTests && docker compose build app && docker compose up -d app`.

**Update 2026-09-01 (previously listed here as unresolved, now fixed):** migrations V19/V21 used to run `CREATE INDEX CONCURRENTLY`, which deadlocks against Flyway's own advisory-lock connection on a fresh database. Fixed by dropping `CONCURRENTLY` from both. If your local DB already had these migrations applied with the old file content before that fix landed, you'll hit a Flyway checksum mismatch on next startup — see `docs/API_TESTING_GUIDE.md` §1 for the repair steps (this is bookkeeping, not a schema problem — the index already exists either way).
