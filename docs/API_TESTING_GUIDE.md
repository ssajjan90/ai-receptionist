# API Testing Guide (callsahayak / ai-receptionist)

Complete reference for testing every API in this application locally: how to start the stack, how to get a JWT for both regular tenants and platform admins, and a full endpoint-by-endpoint walkthrough with working `curl` examples. Every example below was executed against a live local instance while writing this guide (2026-09-03) — not hypothetical.

A companion Postman collection (`postman/`) covers the same ground with click-to-run requests and auto-saved variables. Use whichever you prefer; they're kept in sync.

The rest of this document (§1 onward) is an organized **reference** — look things up by module. If you want to actually run through the whole app end-to-end right now, follow the walkthrough below instead: it's the same material, sequenced as literal steps you can paste into one terminal session, each one testing a specific piece of functionality and building on the last (register → login → onboard → connect WhatsApp → knowledge base → leads → voice → admin). Every command in it is copy-paste runnable and was verified working while writing this guide.

---

## Step-by-Step Walkthrough

Run these in order in one terminal. Each step names the functionality it exercises and roughly how long it takes. Steps marked **(optional)** can be skipped without breaking later steps; steps marked **(destructive)** should only be run against throwaway test data.

### Phase A — Get the app running (Steps 1-2)

**Step 1 — Start Postgres, Redis, and the app.**
```bash
cd /Users/macbookair/Desktop/Suresh/ai-receptionist
docker compose up -d postgres redis
./mvnw spring-boot:run > /tmp/app.log 2>&1 &
sleep 25   # first boot takes a while
```
If you already have the app running in your IDE, skip this — just make sure you know which port it's on (IDE/direct run = 8081, `docker compose up --build` for everything = 8080) and set `BASE` accordingly below. If port 8081 is already taken, check what's holding it (`lsof -i :8081`) before killing anything — it may be your own IDE session.

**Step 2 — Confirm it's healthy.** *(Tests: app startup, DB connectivity, Redis connectivity.)*
```bash
BASE=http://localhost:8081
curl -s $BASE/actuator/health | python3 -m json.tool
```
Expect `"status": "UP"`. If you get a Flyway checksum error instead of a clean start, see §1's troubleshooting note below before continuing.

### Phase B — Tenant onboarding and login (Steps 3-6)

**Step 3 — Register a new tenant.** *(Tests: tenant registration, OTP generation.)*
```bash
curl -s -X POST $BASE/v1/tenants/register -H "Content-Type: application/json" -d '{
  "businessName":"Demo Electronics","ownerName":"Demo Owner",
  "ownerPhone":"+919812340001","businessPhone":"+919812340002",
  "email":"demo.owner@example.com","password":"Password@123"
}'
```
Copy the `tenantId` from the response into `TENANT_ID=<...>`. Status will be `PENDING_VERIFICATION`, tier `BASIC`.

**Step 4 — Read the OTP the app "sent".** *(Tests: nothing new — just retrieving what Step 3 triggered. Locally, OTP delivery is a log line, not a real WhatsApp send.)*
```bash
grep "Sending OTP" /tmp/app.log | tail -1
```
(If running via `docker compose`, use `docker logs ai-receptionist-app-1 --since 1m | grep "Sending OTP"` instead.)

**Step 5 — Verify the OTP — this is the login step.** *(Tests: OTP verification, JWT issuance.)*
```bash
curl -s -X POST $BASE/v1/tenants/verify-otp -H "Content-Type: application/json" -d '{
  "ownerPhone":"+919812340001","otp":"<the 6-digit code from Step 4>"
}'
```
Copy the `jwt` from the response into `JWT=<...>`. This is your owner bearer token for every step below until Phase G.

**Step 6 — Complete onboarding.** *(Tests: onboarding flow, automatic knowledge-base seeding.)*
```bash
curl -s -X PUT "$BASE/v1/tenants/$TENANT_ID/onboarding" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{
  "shopName":"Demo Electronics","location":"Andheri West, Mumbai","businessHours":"10am-8pm",
  "preferredLanguage":"en",
  "topProducts":[{"productName":"Samsung Galaxy S24","price":"74999"}],
  "commonFaqs":[{"question":"Do you offer EMI?","answer":"Yes."}]
}'
```
Expect `kbEntriesCreated: 2` and status `ONBOARDING_COMPLETE`.

### Phase C — WhatsApp connection and messaging (Steps 7-9)

**Step 7 — Connect a WhatsApp number.** *(Tests: WABA linking, tenant activation.)*
```bash
PNI="phone-id-test-$(date +%s)"
curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/whatsapp/connect" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{
  "wabaId":"waba-test-'"$(date +%s)"'","phoneNumberId":"'"$PNI"'",
  "displayPhoneNumber":"+919812340002"
}'
```
Expect tenant status to flip to `LIVE`. (Use a fresh timestamp suffix each run — reusing the same `phoneNumberId` across runs gives `PHONE_ALREADY_REGISTERED`.) Keep `$PNI` — Steps 8-9 need it.

**Step 8 — Simulate a customer message.** *(Tests: inbound message routing, AI response pipeline. Needs a real OpenAI key to produce a real answer — without one, expect a fallback/error, which still proves routing works.)*

**Important:** `To` must be the `phoneNumberId` from Step 7 (`$PNI`), **not** the business phone number. The controller resolves the tenant by `phoneNumberId`, not by the phone number string — using the raw number here silently no-ops (200 response, but the app log shows `No tenant found for Twilio To=...` and nothing is written). This was wrong in this guide's first draft and in the Postman collection's fixtures since they were first written (2026-07-16) — fixed 2026-09-03.
```bash
curl -s -X POST "$BASE/webhooks/whatsapp/twilio" -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "From=whatsapp:+919999999999" \
  --data-urlencode "To=whatsapp:$PNI" \
  --data-urlencode "Body=Do you have Samsung Galaxy S24 in stock?" \
  --data-urlencode "MessageSid=SM_test_$(date +%s)"
```

**Step 9 — Simulate the owner managing the KB via WhatsApp.** *(Tests: owner-command detection, KB write via chat.)* Same `To=$PNI` requirement as Step 8.
```bash
curl -s -X POST "$BASE/webhooks/whatsapp/twilio" -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "From=whatsapp:+919812340001" \
  --data-urlencode "To=whatsapp:$PNI" \
  --data-urlencode "Body=ADD FAQ: Do you offer home delivery? | Yes, free delivery within 5km." \
  --data-urlencode "MessageSid=SM_test_$(date +%s)"
```
Both Step 8 and Step 9 are processed **asynchronously** (`@ApplicationModuleListener`) — the curl call itself always returns `200` immediately with an empty body regardless of what happens downstream; that's the webhook contract, not an error. Give it a few seconds, then check Step 10. If nothing shows up after that, check the app log for exceptions — async listener dispatch has been observed to be unreliable in some local/CI environments independent of anything in the request itself (a known, tracked infrastructure issue, not specific to this endpoint).

### Phase D — Knowledge base CRUD (Steps 10-12)

**Step 10 — List entries.** *(Confirms Steps 6 and 9 both landed data — see Step 9's async caveat if the FAQ from Step 9 isn't there yet.)*
```bash
curl -s "$BASE/v1/tenants/$TENANT_ID/knowledge-base?page=0&size=20" -H "Authorization: Bearer $JWT"
```
Expect at least 3 entries (2 from onboarding, 1 from the WhatsApp `ADD FAQ` command).

**Step 11 — Create, update, delete an entry directly.** *(Tests: manual KB management API, independent of WhatsApp/onboarding.)*
```bash
ENTRY_ID=$(curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/knowledge-base" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{"type":"PRODUCT","productName":"OnePlus 12","answer":"Available, Rs 64999","price":"64999"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X PUT "$BASE/v1/tenants/$TENANT_ID/knowledge-base/$ENTRY_ID" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{"price":"62999"}'

curl -s -w "\nHTTP:%{http_code}\n" -X DELETE "$BASE/v1/tenants/$TENANT_ID/knowledge-base/$ENTRY_ID" -H "Authorization: Bearer $JWT"
```
Expect `204` on delete.

**Step 12 (optional) — OCR price-list import.** *(Tests: image upload + OCR parsing. Needs `OCR_PROVIDER` credentials configured to produce real output; without them the request shape still validates.)* See §6 below for the multipart command — not included here since it needs a real image file.

### Phase E — Leads (Step 13)

**Step 13 — List leads.** *(Tests: lead pipeline. Leads only appear once the AI pipeline in Step 8 detects purchase intent and completes — needs a real LLM key. An empty list here is expected without one, and that's still a valid test result.)*
```bash
curl -s "$BASE/v1/tenants/$TENANT_ID/leads?page=0&size=20" -H "Authorization: Bearer $JWT"
```
If a lead exists, grab its `id` as `LEAD_ID` and try `PATCH .../leads/$LEAD_ID` (see §7) to test status transitions.

### Phase F — Voice (Steps 14-15)

**Step 14 — Inbound call, default (BASIC) tier.** *(Tests: Exotel webhook signature verification, tier gating — the "block low-tier callers" path.)*
```bash
CALLSID="CA_test_$(date +%s)"
CANONICAL="${CALLSID}+919999999999+919812340002inboundringing"
SIG=$(python3 -c "import hmac,hashlib; print(hmac.new(b'test-exotel-shared-secret', b'$CANONICAL', hashlib.sha1).hexdigest())")

curl -s -X POST "$BASE/webhooks/voice" -H "X-Exotel-Signature: $SIG" \
  --data-urlencode "CallSid=$CALLSID" --data-urlencode "From=+919999999999" \
  --data-urlencode "To=+919812340002" --data-urlencode "Direction=inbound" --data-urlencode "Status=ringing"
```
Expect ExoML with `<Play>...WhatsApp...</Play><Hangup/>` — the redirect path, since new tenants default to BASIC.

**Step 15 — Same call, PRO tier.** *(Tests: the AI-handoff path — proves tier gating actually branches, not just that it always redirects.)*
```bash
docker exec ai-receptionist-postgres-1 psql -U postgres -d aireceptionist -c "UPDATE tenants SET tier='PRO' WHERE id='$TENANT_ID';"
# re-run the exact same curl block from Step 14 (with a new CallSid/signature)
```
Expect ExoML with `<Record action="/webhooks/voice/transcript" .../>` this time.

### Phase G — Admin (Steps 16-20)

**Step 16 — Mint an admin token.** *(There's no admin login endpoint — this is the only way.)*
```bash
ADMIN_JWT=$(node scripts/mint-admin-jwt.js)
```

**Step 17 — Dashboard + tenant detail.** *(Tests: cross-tenant admin visibility.)*
```bash
curl -s "$BASE/v1/admin/tenants?page=0&size=20" -H "Authorization: Bearer $ADMIN_JWT"
curl -s "$BASE/v1/admin/tenants/$TENANT_ID" -H "Authorization: Bearer $ADMIN_JWT"
```

**Step 18 — Suspend, then reactivate the tenant.** *(Tests: tenant lifecycle control, and that suspension actually shows up in the tenant's own status.)*
```bash
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/suspend" -H "Authorization: Bearer $ADMIN_JWT"
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/reactivate" -H "Authorization: Bearer $ADMIN_JWT"
```
Watch the `status` field flip `LIVE → SUSPENDED → LIVE` across the two responses.

**Step 19 — Notify and broadcast.** *(Tests: admin-to-owner messaging, rate limiting.)*
```bash
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/notify" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_JWT" -d '{"message":"Test notification from the walkthrough."}'
```

**Step 20 — Query the audit log.** *(Tests: keyset-paginated audit trail — and confirms every admin action from Steps 17-19 was actually logged.)*
```bash
curl -s "$BASE/v1/admin/audit-log?tenantId=$TENANT_ID&size=10" -H "Authorization: Bearer $ADMIN_JWT"
```
You should see `ADMIN_SUSPEND`, `ADMIN_REACTIVATE`, and `ADMIN_AUDIT_VIEW` entries from the steps above. If `hasMore: true`, pass the response's `nextCursorOccurredAt`/`nextCursorId` back as query params to page further (see §9 for the exact mechanics — it's cursor-based, not `page=N`).

### Phase H — Data rights (optional, destructive — Step 21)

**Step 21 (optional, destructive) — DPDP export and erase.** *(Tests: data export format, and — if you erase — that the tenant's data is actually gone.)*
```bash
curl -s "$BASE/v1/tenants/$TENANT_ID/export" -H "Authorization: Bearer $JWT"   # safe, read-only

# Only if you're done with this test tenant and want to confirm erasure works:
curl -s -w "\nHTTP:%{http_code}\n" -X DELETE "$BASE/v1/tenants/$TENANT_ID" -H "Authorization: Bearer $JWT"
```
Erasing is immediate and irreversible — `TENANT_ID`/`JWT` are dead afterward. Re-run from Step 3 with a new phone number to start a fresh walkthrough.

### Done

That covers registration, login, onboarding, WhatsApp connection and messaging, knowledge base CRUD, leads, both voice-tier paths, and the full admin surface including audit trail — every controller in the app. For endpoint variations not shown above (pagination options, filters, error cases) or to re-run any one piece in isolation later, use the reference sections below (§1-§11) or the Postman collection (§10).

---

## 1. Start the stack

```bash
cd /Users/macbookair/Desktop/Suresh/ai-receptionist
docker compose up -d postgres redis      # DB + cache only
./mvnw spring-boot:run                   # runs with the `local` Spring profile config baked into pom/IDE defaults
```

If you run via IntelliJ's own Run configuration instead, do that — don't also run `mvnw spring-boot:run` at the same time, they'll fight over port 8081.

Confirm it's up:

```bash
curl -s http://localhost:8081/actuator/health | python3 -m json.tool
```

Expect `"status": "UP"` with `db` and `redis` both `UP`. Swagger UI is at `http://localhost:8081/swagger-ui.html`.

**Port note:** running directly (`mvnw spring-boot:run` / IntelliJ) uses port **8081** (`application-local.yml`). Running the full stack via `docker compose up --build` maps the app container's 8081 to host port **8080** instead (see `docker-compose.yml`). Match whichever `baseUrl` you use to how you started it.

**If the app won't start with a Flyway checksum mismatch** (`Migration checksum mismatch for migration version NN`): a migration file was edited after your local DB already recorded it applied. Verify the *effect* is still correct (the referenced index/constraint exists as described in the current file), then repair the bookkeeping directly:

```bash
docker exec ai-receptionist-postgres-1 psql -U postgres -d aireceptionist -c \
  "UPDATE flyway_schema_history SET checksum = <Resolved-locally-value-from-the-error> WHERE version = '<NN>';"
```

Use the exact "Resolved locally" number from the error message for each affected version. Never repair blindly — first confirm the DB schema already matches what the current migration file describes.

---

## 2. Authentication — how to get a token

Every non-`permitAll` endpoint needs `Authorization: Bearer <JWT>`. The JWT is an RS256-signed token (see `JwtTokenProvider`) carrying four claims: `tenantId`, `userId`, `role`, `tier`. `role` is what `@PreAuthorize`/route checks key off (`OWNER` for tenant self-service, `PLATFORM_ADMIN` for admin endpoints). There is **no separate `/login` endpoint** — for owners, `verify-otp` *is* the login step, and it always follows a fresh registration; there's no way to re-login as an existing tenant. For admins, there's no HTTP path to a token **at all** (see 2.2).

### 2.1 Owner JWT (real flow, via the API)

```bash
BASE=http://localhost:8081

# 1. Register — creates the tenant (status PENDING_VERIFICATION) and sends a 6-digit OTP.
#    Locally, "sending" just logs it (WhatsAppOwnerNotificationAdapter never calls a real API).
curl -s -X POST $BASE/v1/tenants/register -H "Content-Type: application/json" -d '{
  "businessName":"Demo Electronics","ownerName":"Demo Owner",
  "ownerPhone":"+919812340001","businessPhone":"+919812340002",
  "email":"demo.owner@example.com","password":"Password@123"
}'
# -> { "data": { "tenantId": "...", "status": "PENDING_VERIFICATION", "tier": "BASIC", ... } }

# 2. Get the OTP from the app's own console/log output:
grep "Sending OTP" /path/to/your/app.log | tail -1
#    or, if running via `docker compose`:  docker logs ai-receptionist-app-1 --since 1m | grep "Sending OTP"

# 3. Verify — this mints the JWT. role=OWNER, tier=BASIC (every new tenant starts BASIC).
curl -s -X POST $BASE/v1/tenants/verify-otp -H "Content-Type: application/json" -d '{
  "ownerPhone":"+919812340001","otp":"<the 6-digit code from the log>"
}'
# -> { "data": { "jwt": "eyJ...", "tenantId": "...", "role": "OWNER", "tier": "BASIC" } }
```

The OTP is HMAC-hashed in Redis (5-minute TTL) — you cannot read it back from Redis directly, only from the app's log line. If it expires, `POST /v1/tenants/resend-otp?ownerPhone=...` issues a fresh one (also logged the same way).

Every owner-scoped endpoint checks that the JWT's `tenantId` claim matches the `{tenantId}` in the URL path — a valid JWT for tenant A gets `403 FORBIDDEN` on tenant B's resources, not a 401.

### 2.2 Admin JWT (PLATFORM_ADMIN) — no login endpoint exists

The `admin_users` table has zero rows in every environment (nothing provisions them yet — this is real, tracked, unfinished work, not an oversight in this guide). The only way to get a `PLATFORM_ADMIN` token is to mint one locally with the same RSA key the app uses:

```bash
node scripts/mint-admin-jwt.js
```

This is a zero-dependency Node script (built-in `crypto` only) that signs a token with the exact private key `application-local.yml` gives the running app — byte-for-byte what `JwtTokenProvider.generateToken(...)` would produce at runtime, just invoked outside the app. It prints one line: the JWT. Save it:

```bash
ADMIN_JWT=$(node scripts/mint-admin-jwt.js)
curl -s "$BASE/v1/admin/tenants?page=0&size=5" -H "Authorization: Bearer $ADMIN_JWT"
```

This only works against the `local` profile's checked-in dev keypair — it will not produce a usable token against a real deployed environment with different keys, by design.

---

## 3. Response envelope

Every JSON endpoint (everything except the raw-XML voice webhook, see §8) returns the same shape:

```json
{
  "success": true,
  "data": { ... },
  "errorCode": null,
  "message": null,
  "details": null,
  "timestamp": "2026-09-03T13:43:26.728239Z"
}
```

On failure, `success: false`, `data: null`, and `errorCode`/`message` populated (e.g. `"errorCode": "VALIDATION_ERROR"`, `"details": {"otp": ["must not be blank"]}`). HTTP status still follows normal REST conventions (400/403/404/409/500 etc.) — check both the status code and `errorCode` when asserting failures.

---

## 4. Tenant self-service API (`/v1/tenants/**`)

All require `Authorization: Bearer <ownerJwt>` except register/verify-otp/resend-otp (`permitAll`). `{tenantId}` in the path must match the JWT's `tenantId` claim.

| # | Method & Path | Purpose | Notes |
|---|---|---|---|
| 1 | `POST /v1/tenants/register` | Create tenant, send OTP | `permitAll`. 201 on success. |
| 2 | `POST /v1/tenants/verify-otp` | Verify OTP, **issue JWT** | `permitAll`. The only login step. |
| 3 | `POST /v1/tenants/resend-otp?ownerPhone=...` | Invalidate + resend OTP | `permitAll`. Query param, not a body. |
| 4 | `PUT /v1/tenants/{tenantId}/onboarding` | Complete onboarding, seed KB | Auth required. Converts `topProducts`/`commonFaqs` into KB entries — good way to get test data into §5 quickly. |
| 5 | `GET /v1/tenants/{tenantId}/export` | DPDP data export | Auth required. Message content comes back as SHA-256 hashes, not raw text. |
| 6 | `DELETE /v1/tenants/{tenantId}` | DPDP erase — **immediate, destructive** | Auth required. 204 on success. Only run against a throwaway test tenant — data is gone immediately, no grace period (unlike admin's terminate, §6). |

```bash
# Complete onboarding (seeds 2 KB entries you can see in §5)
curl -s -X PUT "$BASE/v1/tenants/$TENANT_ID/onboarding" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{
  "shopName":"Demo Electronics","location":"Andheri West, Mumbai","businessHours":"10am-8pm",
  "preferredLanguage":"en",
  "topProducts":[{"productName":"Samsung Galaxy S24","price":"74999"}],
  "commonFaqs":[{"question":"Do you offer EMI?","answer":"Yes."}]
}'
```

---

## 5. WhatsApp connection & simulated inbound messages

```bash
# Connect a (fake, local-only) WhatsApp Business number. wabaId/phoneNumberId must be
# globally unique across all tenants — reusing a fixture value gives PHONE_ALREADY_REGISTERED.
PNI="phone-id-test-$(date +%s)"
curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/whatsapp/connect" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{
  "wabaId":"waba-test-'"$(date +%s)"'","phoneNumberId":"'"$PNI"'",
  "displayPhoneNumber":"+919812340002"
}'
# -> tenant status flips to LIVE on success. Keep $PNI - the webhook calls below need it.
```

Real Meta webhooks (`/webhooks/whatsapp`) require an HMAC-SHA256 signature (`X-Hub-Signature-256`) you can't easily fake without the app secret. For local testing, use the **Twilio-shaped sandbox webhook instead — no signature required**.

**`To` must be `$PNI` (the `phoneNumberId` from the connect call above), not the business phone number** — `TwilioWebhookController` resolves the tenant via `resolveByPhoneNumberId(toPhone)`, so a raw phone number here matches nothing: you get a `200` back (the webhook always acks), but the app log shows `No tenant found for Twilio To=...` and nothing is written. (This was wrong in this guide's and the Postman collection's fixtures from when they were first written until 2026-09-03.)

```bash
# Simulate an inbound customer message (routes through the AI pipeline — needs a real
# OpenAI key configured to produce a real answer; without one, expect a fallback/error,
# which is still fine for testing routing).
curl -s -X POST "$BASE/webhooks/whatsapp/twilio" -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "From=whatsapp:+919999999999" \
  --data-urlencode "To=whatsapp:$PNI" \
  --data-urlencode "Body=Do you have Samsung Galaxy S24 in stock?" \
  --data-urlencode "MessageSid=SM_test_$(date +%s)"

# Simulate the owner sending a KB-management command (From = the registered ownerPhone
# routes to OwnerCommandParser instead of the AI pipeline)
curl -s -X POST "$BASE/webhooks/whatsapp/twilio" -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "From=whatsapp:+919812340001" \
  --data-urlencode "To=whatsapp:$PNI" \
  --data-urlencode "Body=ADD FAQ: Do you offer home delivery? | Yes, free delivery within 5km." \
  --data-urlencode "MessageSid=SM_test_$(date +%s)"
```

Both calls are processed asynchronously (`@ApplicationModuleListener`) — a `200` with an empty body just means the webhook accepted the request, not that processing finished or succeeded. Give it a few seconds and check the relevant list endpoint (knowledge-base for the FAQ command, leads for purchase-intent detection); if nothing shows up, check the app log rather than assuming the curl call was wrong — async listener dispatch has been observed to be unreliable in some local/CI environments, independent of the request itself (a known, tracked infrastructure issue).

Do not expose `/webhooks/whatsapp/twilio` outside local/dev — it has zero signature verification by design.

---

## 6. Knowledge base (`/v1/tenants/{tenantId}/knowledge-base/**`)

All require owner JWT.

```bash
curl -s "$BASE/v1/tenants/$TENANT_ID/knowledge-base?page=0&size=20" -H "Authorization: Bearer $JWT"

curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/knowledge-base" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{
  "type":"PRODUCT","productName":"OnePlus 12","answer":"OnePlus 12 available, price Rs 64999","price":"64999"
}'
# type must be a valid EntryType: PRODUCT | FAQ | SERVICE — anything else 400s.

curl -s -X PUT "$BASE/v1/tenants/$TENANT_ID/knowledge-base/$ENTRY_ID" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{"price":"62999"}'

curl -s -X DELETE "$BASE/v1/tenants/$TENANT_ID/knowledge-base/$ENTRY_ID" -H "Authorization: Bearer $JWT"
```

OCR import (`POST .../ocr-import` with a multipart `image` field, JPEG/PNG, max 5MB, then `POST .../ocr-import/confirm` with the parsed entries) needs real OCR provider credentials (`OCR_PROVIDER`) configured to do anything beyond validating the request shape locally.

---

## 7. Leads (`/v1/tenants/{tenantId}/leads/**`)

All require owner JWT. Leads only appear once the AI pipeline detects purchase intent from an inbound WhatsApp message (§5) — that needs a real LLM key configured, so an empty list locally is expected without one.

```bash
curl -s "$BASE/v1/tenants/$TENANT_ID/leads?page=0&size=20" -H "Authorization: Bearer $JWT"
curl -s -X PATCH "$BASE/v1/tenants/$TENANT_ID/leads/$LEAD_ID" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" -d '{"status":"CONTACTED"}'
  # status: NEW | CONTACTED | CONVERTED | DISMISSED
curl -s "$BASE/v1/tenants/$TENANT_ID/leads/export" -H "Authorization: Bearer $JWT"
curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/leads/$LEAD_ID/erase" -H "Authorization: Bearer $JWT"
curl -s -X POST "$BASE/v1/tenants/$TENANT_ID/leads/erase-all" -H "Authorization: Bearer $JWT"
```

---

## 8. Voice / Exotel webhook (`POST /webhooks/voice`) — Story 6.1

`permitAll`, but **signature-verified** — unlike the Twilio WhatsApp sandbox. Returns raw ExoML (XML), not the JSON envelope. Exotel sends `CallSid`/`From`/`To`/`Direction`/`Status` as form fields; the signature is `hex(HMAC-SHA1(sharedSecret, CallSid+From+To+Direction+Status))` in the `X-Exotel-Signature` header. Local default secret is `test-exotel-shared-secret` (`app.exotel.shared-secret` in `application.yml`).

```bash
BIZPHONE="+919812340002"
CALLSID="CA_test_$(date +%s)"
CANONICAL="${CALLSID}+919999999999${BIZPHONE}inboundringing"
SIG=$(python3 -c "import hmac,hashlib; print(hmac.new(b'test-exotel-shared-secret', b'$CANONICAL', hashlib.sha1).hexdigest())")

curl -s -X POST "$BASE/webhooks/voice" -H "X-Exotel-Signature: $SIG" \
  --data-urlencode "CallSid=$CALLSID" \
  --data-urlencode "From=+919999999999" \
  --data-urlencode "To=$BIZPHONE" \
  --data-urlencode "Direction=inbound" \
  --data-urlencode "Status=ringing"
```

New tenants default to `tier=BASIC`, so expect:
```xml
<Response><Play>Please visit our WhatsApp at +91XXXXXXXXXX for assistance.</Play><Hangup/></Response>
```

No tier-upgrade endpoint exists yet (Epic 7 billing isn't built) — to see the PRO/ENTERPRISE AI-handoff path (`<Record>`), promote the tenant directly:

```bash
docker exec ai-receptionist-postgres-1 psql -U postgres -d aireceptionist \
  -c "UPDATE tenants SET tier='PRO' WHERE id='$TENANT_ID';"
```
Then re-run the same curl — expect:
```xml
<Response><Record action="/webhooks/voice/transcript" maxLength="30"/></Response>
```

An invalid/missing signature, or an unrecognized `To` number, both return `200` with a generic "unable to process" ExoML message and do **not** create a `VoiceCall` record — by design (webhooks must always 200, and Exotel's own retry/signature-canonicalization isn't independently documented, see the controller's own javadoc for that caveat).

---

## 9. Admin API (`/v1/admin/**`) — Epic 5

All require `Authorization: Bearer <adminJwt>` (role=`PLATFORM_ADMIN`, see §2.2). `tenantId` is a path variable everywhere except the audit-log endpoint, where it's a required query param.

```bash
ADMIN_JWT=$(node scripts/mint-admin-jwt.js)

# Dashboard listing
curl -s "$BASE/v1/admin/tenants?page=0&size=20" -H "Authorization: Bearer $ADMIN_JWT"
curl -s "$BASE/v1/admin/tenants/$TENANT_ID" -H "Authorization: Bearer $ADMIN_JWT"

# Data export (admin copy of §4's owner export — any admin, any tenant)
curl -s "$BASE/v1/admin/tenants/$TENANT_ID/export" -H "Authorization: Bearer $ADMIN_JWT"

# Conversation log (optional from/to as ISO-8601 instants)
curl -s "$BASE/v1/admin/tenants/$TENANT_ID/conversations?page=0&size=20" -H "Authorization: Bearer $ADMIN_JWT"

# Lifecycle actions
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/suspend" -H "Authorization: Bearer $ADMIN_JWT"
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/reactivate" -H "Authorization: Bearer $ADMIN_JWT"
curl -s -X DELETE "$BASE/v1/admin/tenants/$TENANT_ID" -H "Authorization: Bearer $ADMIN_JWT"
  # Terminate: NOT immediate like tenant self-erase — schedules real erasure 30 days out.
  # Safe to run against a test tenant.

# Notifications (rate-limited: 10/min per admin, shared bucket between these two)
curl -s -X POST "$BASE/v1/admin/tenants/$TENANT_ID/notify" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_JWT" -d '{"message":"Scheduled maintenance tonight 11pm-12am IST."}'

curl -s -X POST "$BASE/v1/admin/broadcast" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_JWT" -d '{"tenantIds":["'"$TENANT_ID"'"],"message":"Voice support is now live."}'
  # tenantIds capped at 100 entries; one call = one rate-limit token regardless of list size.

# Audit log — KEYSET pagination, not page numbers (see below)
curl -s "$BASE/v1/admin/audit-log?tenantId=$TENANT_ID&size=10" -H "Authorization: Bearer $ADMIN_JWT"
```

**Audit log pagination is cursor-based, not `page=N`.** The response looks like:
```json
{ "data": { "content": [...], "hasMore": true, "nextCursorOccurredAt": "2026-...", "nextCursorId": "..." } }
```
When `hasMore: true`, pass those two values back as `cursorOccurredAt`/`cursorId` query params to get the next page:
```bash
curl -s "$BASE/v1/admin/audit-log?tenantId=$TENANT_ID&size=10&cursorOccurredAt=$NEXT_OCCURRED_AT&cursorId=$NEXT_ID" \
  -H "Authorization: Bearer $ADMIN_JWT"
```
This design exists specifically because the endpoint writes its own `ADMIN_AUDIT_VIEW` audit row into the same table it queries — offset/page-number pagination would drift under that self-write; keyset pagination doesn't. Rows older than 90 days are always excluded, unconditionally. Optional filters: `eventType`, `from`, `to` (ISO-8601 instants).

Every admin action above writes its own audit trail entry (`ADMIN_SUSPEND`, `ADMIN_REACTIVATE`, `ADMIN_DATA_EXPORT`, `ADMIN_CONVERSATION_VIEW`, `ADMIN_AUDIT_VIEW`, etc.) — visible immediately via 5.10/audit-log.

---

## 10. Using the Postman collection instead

`postman/AI-Receptionist.postman_collection.json` + `postman/AI-Receptionist-Local.postman_environment.json` cover everything in this guide as click-to-run requests, with test scripts that auto-save `tenantId`/`ownerJwt`/`entryId`/`leadId`/audit-log cursors into environment variables as you go — you rarely need to copy-paste anything by hand. Import both, select the **AI Receptionist - Local** environment, and work through folders 1→6 in order. Full walkthrough notes: `postman/README.md`.

The Voice folder's request has a pre-request script that computes the Exotel HMAC signature automatically (same formula as §8) — no manual signing needed there.

---

## 11. Known gaps (not bugs — just not built yet)

- No login endpoint for a *returning* owner — `verify-otp` only follows a fresh `register` call.
- No admin login/registration endpoint at all — `scripts/mint-admin-jwt.js` is the only way to test `/v1/admin/**` locally.
- No tier-upgrade endpoint — Epic 7 (billing) isn't built; promote a tenant's tier directly in the DB to test PRO/ENTERPRISE-gated behavior (voice AI handoff, etc.).
- Franchise/multi-location endpoints (Epic 8) have no controllers yet.
- OCR and the AI response pipeline need real provider credentials (`OCR_PROVIDER`, `SPRING_AI_OPENAI_API_KEY`) to produce real output locally — without them, expect fallback/error responses, which are still valid for testing routing and error handling.

---

## 12. Troubleshooting

- **"Port 8081 already in use"** — something (your IDE's own run/debug session, most likely) already has it. Check before killing anything: `lsof -i :8081` and inspect the command before `kill`-ing a PID — don't assume it's a stray process.
- **Full `mvn verify` test suite failing with `CannotGetJdbcConnection`/`kbUpdateListenerContainer` errors** — this is a known Testcontainers/Docker resource-contention issue when running the whole ~284-test suite as one batch, tracked separately; it's not something this guide's manual API testing hits (each `curl` call here talks to one already-running app instance, not a fresh Testcontainers context per call).
- **Flyway checksum mismatch on startup** — see §1's troubleshooting note.
- **`PHONE_ALREADY_REGISTERED` on WhatsApp connect** — the `phoneNumberId`/`wabaId` you used already belongs to another tenant in your local DB (common if reusing the same fixture values across multiple test runs). Use a unique value per run, e.g. append `$(date +%s)`.
