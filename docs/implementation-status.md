# AI Receptionist — Implementation Status

**Last updated:** 2026-09-17
**Project:** Multi-tenant B2B SaaS — AI-powered WhatsApp + Voice receptionist for Indian SMBs
**Stack:** Java 17, Spring Boot + Spring Modulith, PostgreSQL (Flyway, RLS), Redis, JWT RS256

**Overall progress:** Far more is built than the previous version of this doc showed. Epics 1–5 (foundation, onboarding, AI WhatsApp pipeline, leads, admin/compliance) are essentially feature-complete end to end. Epic 6 (Voice) has only its first story done. Epics 7 (Billing) and 8 (Franchise) have not been started. The most urgent issue right now is not a missing feature — it's that **CI on `main` is currently red** (see Testing & CI below).

---

## System Overview

A Spring Boot backend (single Maven module at repo root, package `com.aireceptionist`) organized as Spring Modulith modules — `tenant`, `knowledgebase`, `whatsapp`, `voice`, `leads`, `billing`, `admin`, `ai`, `common` — each following a hexagonal layout (`domain` / `application` / `port/in` / `port/out` / `adapter/in` / `adapter/out`) as described in `docs/hexagonal-architecture.md`. Tenant data is isolated at the Postgres level via row-level security, not just application code. Business events (inbound WhatsApp/voice messages, KB changes, leads) flow through Spring Modulith's `@ApplicationModuleListener` event bus rather than direct cross-module calls.

---

## Epic-by-Epic Status

| Epic | Status | Notes |
|------|--------|-------|
| 1 — Platform Foundation & Infrastructure | **Done** | Modules, RLS, JWT, Redis, resilience, audit, API envelope, CI all in place (CI itself currently failing — see below) |
| 2 — Tenant Registration & Onboarding | **Done** | Registration, OTP, 5-question wizard, WhatsApp connect, OCR import all implemented and tested |
| 3 — AI WhatsApp Customer Service & KB | **Done** | Full pipeline: language detection, LLM + KB context, confidence gate, empathy mode, lead capture, WhatsApp owner commands, self-improving KB, web KB CRUD |
| 4 — Lead Management & Owner Visibility | **Done** | List/filter/status update, export, erase/erase-all, daily summary job (built, flagged off by default) |
| 5 — Platform Administration & Compliance | **Done** | Dashboard, suspend/reactivate/terminate, conversation log viewer, broadcast/notify, audit log query, data export |
| 6 — Voice Communication *(Phase 1b)* | **Partial** | Story 6.1 only (call reception + tier gating). No AI voice response, no human fallback/transfer |
| 7 — Subscription & Billing *(Phase 1b)* | **Not started** | Only a module-boundary test stub exists. No Razorpay, no tier enforcement, no usage tracking |
| 8 — Franchise & Multi-Location *(Phase 2)* | **Not started** | No code found anywhere in `src/` |

---

### Epic 1 — Platform Foundation & Infrastructure (Done)

- **Migrations:** 26 Flyway migrations now applied (V1–V26), up from 13 in the last doc — later ones are indexes, constraint fixes, and additive columns (e.g. `V21__add_leads_tenant_status_created_index.sql`, `V25__fix_whatsapp_sender_type_constraint.sql`, `V26__add_leads_phone_hash.sql`), not new tables.
- **Multi-tenancy:** RLS + `TenantContext`/`TenantIdentifierResolver`/`TenantConnectionProvider` unchanged in approach.
- **Module boundary testing changed:** `AbstractModuleTest` no longer uses `@ApplicationModuleTest` (a real Spring context per module) — code comments explain this broke once cross-module bean dependencies existed. It now does pure static bytecode analysis via `ApplicationModules.of(...).detectViolations()`, with one **documented, accepted exception**: a `leads` ↔ `whatsapp` cycle (a deliberate bidirectional event saga — WhatsApp captures a lead → leads reacts; leads module also notifies the owner via WhatsApp). Any other violation still fails the build.
- **AI confidence gate, resilience, audit log, API envelope, Swagger:** all as previously documented, still in place.
- **CI:** GitHub Actions workflow now splits ~284 tests into 8 parallel job groups (was 1 monolithic job) because Testcontainers Postgres/Redis became unreachable partway through long single-JVM runs. See Testing & CI section — this fix did not fully resolve the underlying flakiness.

### Epic 2 — Tenant Registration & Onboarding (Done)

Unchanged in substance from the previous doc: registration + WhatsApp OTP, 5-question onboarding wizard, WhatsApp Business connect flow, OCR price-list import (Google Vision default / Tesseract fallback), DPDP export/erasure endpoints. All backed by tests (`TenantRegistrationTest`, `OtpServiceTest`, `TenantOnboardingTest`, `TenantDataExportTest`, `TenantErasureTest`, etc.).

### Epic 3 — AI WhatsApp Customer Service & Knowledge Base (Done — major change since last doc)

The previous doc described this as "Not started" with `LlmService` as a stub. That is now wrong — this is fully wired:

- **`LlmService`** (`src/main/java/com/aireceptionist/ai/service/LlmService.java`) calls a real `AiChatPort` (implemented by `SpringAiChatAdapter`), builds prompts via `PromptAssembler` with KB context from `KnowledgeBaseService.search()` and last-5-turn conversation history from `ConversationContextService`, caches responses in Redis (`tenant:{id}:query:{hash}`), and has separate normal vs. empathy-mode response generation, both behind `@CircuitBreaker` and the `@AiResponse` confidence-gate aspect.
- **`WhatsAppMessageService.onInboundMessage()`** is the actual end-to-end pipeline: language detection → conversation history → frustration/empathy detection (publishes `FrustrationDetectedEvent`) → LLM call → lead-capture state machine (consent message → name/phone parse → `LeadCaptureRequestedEvent`) → persists + sends the outbound WhatsApp message. It also correctly blocks message processing for `SUSPENDED` / `PAYMENT_SUSPENDED` / `TERMINATED` tenants (fixed reply) and silently drops messages for `ERASED` tenants (documented DPDP reasoning in code comments).
- **Owner WhatsApp commands** (`OwnerCommandParser`): `ADD:`, `UPDATE:`, `DELETE:`, `ADD FAQ:` commands, with `ConflictDetectionService` checking for conflicting entries before saving.
- **Self-improving KB:** `UnansweredQueryListener` notifies the owner on a low-confidence query and stores training context in Redis; owner's reply completes the loop (per `KnowledgeBaseWebCrudTest`/`SelfImprovingKbTest`).
- **Web KB interface:** `KnowledgeBaseController` has full CRUD + pagination + OCR import/confirm, tenant-ownership-checked.
- **Caveat:** several of the async, event-driven integration tests for this exact pipeline (confidence gating, frustration mode, AI pipeline end-to-end) are among the tests currently **failing in CI** — see Testing & CI. The code exists and unit-level pieces pass; the async wiring's reliability under CI's Testcontainers setup is currently in question.

### Epic 4 — Lead Management & Owner Visibility (Done)

- `LeadController`: list with status filter, `PATCH` status update, export, erase single lead, erase-all.
- `DailySummaryJob`: scheduled job exists and is fully implemented, gated behind `features.daily-summary.enabled` (defaults to `false`), matching the plan's Phase-1b flag intent.
- `LeadCaptureEventListener` ties the WhatsApp-side consent flow (Epic 3) into actual `Lead` persistence.

### Epic 5 — Platform Administration & Compliance (Done)

All six planned stories appear implemented in `AdminController` / `AdminService`:
- Tenant dashboard list + detail (`GET /v1/admin/tenants`, `/{tenantId}`)
- Suspend / reactivate / terminate actions
- Conversation log viewer (read-only, audited access)
- Admin → owner WhatsApp notify + rate-limited broadcast (capped tenant list, shared rate bucket with `/notify`)
- Tenant data export
- Audit log query endpoint — uses **keyset/seek pagination** (not offset) deliberately, per an in-code note referencing a 2026-09-03 review
- `AdminAccessAuditInterceptor` / `ScheduledDataRetentionJob` for admin access logging and 90-day cleanup

**Known gap:** there is still no login endpoint that issues a `PLATFORM_ADMIN` JWT (`admin_users` table exists, but nothing authenticates against it). The only way to get an admin token today is the local `scripts/mint-admin-jwt.js` script, which signs one locally using the app's own RSA key. This is fine for local testing but is a real gap before this could go to production.

### Epic 6 — Voice Communication *(Phase 1b)* — Partial

- **Done (Story 6.1):** `VoiceWebhookController` receives Exotel call webhooks, verifies an HMAC-SHA1 signature over the form fields, resolves the tenant by business phone number, and redirects `BASIC`-tier callers to WhatsApp per plan. `ExotelCallService` persists a `VoiceCall` row via an `@ApplicationModuleListener`. Voice deliberately shares no service/repository/queue with `whatsapp` (independent-failure requirement satisfied by construction).
- **Not done (Stories 6.2–6.4):** No actual AI voice response — no STT, no TTS, no `LlmService` call from the voice path. The `/webhooks/voice/transcript` action is referenced in the ExoML response XML but has no corresponding controller endpoint. No human-fallback alert or live call transfer. A code comment in `ExotelCallService` explicitly flags this as a placeholder: "The AI voice pipeline itself is wired in Story 6.2" — i.e. that story has not happened yet.

### Epic 7 — Subscription & Billing Automation *(Phase 1b)* — Not started

`BillingModule` exists only as a module-boundary test (`BillingModuleTest`) with no production code behind it. No Razorpay integration, no `@TierRequired`-style enforcement annotation, no usage/limit tracking, no suspension-on-non-payment flow. The only billing-adjacent code is on the **tenant** side: `SubscriptionProvisioningPort` / `JdbcSubscriptionProvisioningAdapter`, which just provisions a default `BASIC` subscription row at registration time — this is tenant-onboarding plumbing, not the billing epic.

### Epic 8 — Franchise & Multi-Location *(Phase 2)* — Not started

No franchise-related code anywhere in `src/`. Consistent with this being explicitly Phase 2 in the plan.

---

## Known Gaps / Not-Yet-Started Work

- **Epic 7 (Billing)** and **Epic 8 (Franchise)** — nothing built.
- **Voice AI response, human fallback, live transfer** (Epic 6, stories 6.2–6.4).
- **No admin login/auth flow** — `PLATFORM_ADMIN` JWTs can currently only be minted by a local script, not issued by the running application.
- **A stale `backend/` directory** at the repo root (`/backend/src/main/java/com/aireceptionist/...`) is a leftover from the very first scaffold commits (April 2026, before the Spring Modulith rewrite to `/src`). It is not referenced by `pom.xml` or `.gitignore` and appears to be dead code sitting in the repo — worth deleting or explicitly archiving so it doesn't confuse future readers.
- **CI reliability** — see below; several async integration tests are currently failing on `main`.

## In-Progress / Uncommitted Work

Two local changes are not yet committed:

- **`scripts/mint-admin-jwt.js` (modified, not committed):** now mints a fixed "super admin" identity (`00000000-0000-0000-0000-000000000001`) with a 30-day expiry instead of a fresh random identity with a 2-hour expiry each run — makes local admin testing (and its audit-log trail) consistent across sessions. Includes a usage example in its own output.
- **`scripts/whatsapp-simulator.js` (new, untracked):** a local dev tool that serves a one-page setup-wizard + chat UI on `localhost:4000`, proxies onboarding calls to the real backend, and builds correctly HMAC-signed Meta-style webhook payloads to exercise `/webhooks/whatsapp` end to end — plus stands in for the WhatsApp Graph API so outbound AI replies can be seen in a browser without a real Meta sandbox. This looks like genuinely useful tooling for manually verifying the Epic 3 pipeline; it should probably be committed once it's confirmed working.

Neither file touches production code — both are developer tooling under `scripts/`.

---

## Testing & CI Status

- **78 test files** under `src/test/java`, spread across every module (roughly: tenant 11, whatsapp 11, leads 12, knowledgebase 9, ai 9, admin 8, security 3, voice 3, common 3, api/audit/billing/ratelimit/resilience 1–2 each, plus 3 root-level: `AbstractIntegrationTest`, `AbstractModuleTest`, `ApplicationContextLoadsTest`).
- **CI workflow** (`.github/workflows/ci.yml`) splits tests into 8 groups (`admin-core`, `admin-audit`, `admin-conversation`, `ai`, `knowledgebase`, `leads`, `tenant`, `whatsapp-voice`), each its own job/runner, after a monolithic single-job run started hitting Testcontainers connection failures under load (documented in the workflow's own comments, dated 2026-09-03).
- **CI is currently failing on `main`.** Checked via `gh run list` / `gh run view`: the last 5 runs on `main` (including both "Existing issues fix" commits) all show **failure**, with only the `admin-audit` group passing on the most recent run. The actual test-log errors point to a **Redis Testcontainers connectivity problem** (`io.lettuce.core.RedisConnectionException: Connection closed prematurely`), which cascades into `org.awaitility.core.ConditionTimeoutException` in async, event-driven integration tests — specifically `ConfidenceGatingIntegrationTest`, `FrustrationModeIntegrationTest`, and `AiPipelineTest` (all Epic 3 pipeline tests that wait on an `@ApplicationModuleListener` to finish). Plain unit tests in the same groups pass fine.
- **Net read:** the underlying Epic 3/5/6 features are real and reasonably well covered by tests, but the test suite's reliability in CI has regressed since the 2026-09-03 test-splitting fix — splitting into groups fixed the original monolithic-run timeout but exposed (or didn't fix) a second, Redis-specific flake. This should not be read as "the features are broken"; it should be read as "CI can't currently be trusted to confirm that."

---

## Suggested Next Steps

1. **Fix the Redis Testcontainers flake in CI** — this is blocking any real confidence in commits since 2026-09-03; look at container lifecycle/readiness handling in the async integration test base rather than the awaitility timeouts themselves (those are a symptom).
2. **Decide the fate of `/backend`** — delete it or document why it's kept; as-is it's confusing dead code next to the real `/src` tree.
3. **Commit or discard** `scripts/mint-admin-jwt.js` and `scripts/whatsapp-simulator.js` — both look like keepers.
4. **Build a real admin login flow** against `admin_users` before relying on the JWT-minting script for anything beyond local dev.
5. **Pick up Epic 6** (Stories 6.2–6.4: AI voice response, human fallback, live transfer) if voice is still in scope for the next milestone — Story 6.1's plumbing (tenant resolution, tier gating, call persistence) is ready to build on.
6. **Epic 7 (Billing)** is the largest remaining greenfield epic — nothing exists yet beyond the module skeleton; scope it as its own block of work when ready.
