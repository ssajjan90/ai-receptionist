# AI Receptionist — Implementation Status

**Last updated:** 2026-05-22  
**Project:** Multi-tenant B2B SaaS — AI-powered WhatsApp + Voice receptionist for Indian SMBs  
**Stack:** Java 17, Spring Boot (Spring Modulith), PostgreSQL, Redis, JWT RS256

---

## Quick Summary

| Epic | Title | Status |
|------|-------|--------|
| Epic 1 | Platform Foundation & Infrastructure | **Complete** |
| Epic 2 | Tenant Registration & Business Onboarding | **Complete** |
| Epic 3 | AI WhatsApp Customer Service & Knowledge Base | **Not started** |
| Epic 4 | Lead Management & Owner Visibility | **Not started (DB schema exists)** |
| Epic 5 | Platform Administration & Compliance | **Partial — infrastructure done, admin UI not started** |
| Epic 6 | Voice Communication *(Phase 1b)* | **Not started** |
| Epic 7 | Subscription & Billing *(Phase 1b)* | **Not started** |
| Epic 8 | Franchise & Multi-Location *(Phase 2)* | **Not started** |

---

## Epic 1 — Platform Foundation & Infrastructure (Complete)

All infrastructure required by business features is in place.

### Module structure
Nine Spring Modulith modules are defined with enforced boundaries:
`tenant`, `knowledgebase`, `whatsapp`, `voice`, `leads`, `billing`, `admin`, `ai`, `common`

Module boundary tests run via `@ApplicationModuleTest` on each module.

### Database — Flyway migrations V1–V13
All 13 migrations applied in order at startup:

| Migration | Table / Change |
|-----------|---------------|
| V1 | `tenants` |
| V2 | `knowledge_entries` |
| V3 | `leads` |
| V4 | `subscriptions` |
| V5 | `whatsapp_messages` |
| V6 | `voice_calls` |
| V7 | `admin_users` |
| V8 | RLS policies (row-level security) |
| V9 | `audit_log` |
| V10 | Extended `tenants` for registration fields |
| V11 | Spring Modulith event publication tables |
| V12 | Onboarding fields + extended `knowledge_entries` |
| V13 | WhatsApp connection fields on `tenants` |

### Multi-tenancy (RLS)
- `TenantIdentifierResolver` — resolves `tenantId` from Spring Security context
- `TenantConnectionProvider` — executes `SET app.current_tenant = ?` per connection
- `TenantContext` — thread-local carrier for tenant ID
- Integration test (`TenantRlsTest`) verifies Tenant A cannot read Tenant B's rows

### Redis — five concerns wired
| Concern | Key pattern | Implementation |
|---------|------------|---------------|
| Response cache | `tenant:{id}:query:{hash}` | `ResponseCacheService` |
| OTP storage (5-min TTL) | `otp:{phone}` | `RedisOtpAdapter` |
| Rate limiting | Per-tenant buckets | `RateLimitFilter` + `TenantRateLimitService` (Bucket4j) |
| WhatsApp outage queue | `queue:whatsapp:{tenantId}` | `WhatsAppQueueService` |
| KB pub/sub propagation | (infrastructure ready) | `KnowledgeEntryAddedEvent` fired on KB changes |

### Security — JWT RS256
- `JwtTokenProvider` — signs (RSASSASigner) and verifies (RSASSAVerifier) tokens; claims: `tenantId`, `userId`, `role`, `tier`
- `JwtAuthenticationFilter` — validates Bearer token on every request; populates `TenantAwareAuthentication`
- `SecurityConfig` — whitelist: `/api/v1/tenants`, `/webhooks/**`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`
- Tests: `JwtTokenProviderTest`, `JwtAuthenticationFilterTest`, `SecurityIntegrationTest`

### Resilience
- `LlmService` annotated with `@CircuitBreaker(name = "llmService", fallbackMethod = "generateResponseFallback")` — opens after 5 failures, half-open probe after 30s
- `FallbackMessageProvider` — returns a graceful customer-facing message on LLM outage

### AI confidence gate (cross-cutting)
- `AiConfidenceGateAspect` (`@Aspect`) intercepts every `@AiResponse`-annotated method
- At confidence < 0.50: replaces response with fallback, logs warning
- At confidence 0.50–0.75: flags response for owner review (`aiResult.flagged()`)
- At confidence ≥ 0.75: passes through unchanged
- Writes an `AuditLogEntry` (tenant ID, event type, confidence score, SHA-256 query hash, timestamp) for every AI call
- `AuditLogCleanupJob` — `@Scheduled(cron = "0 0 2 * * *")` deletes rows older than 90 days

### API design
- `VersionedRestController` — header-based versioning: `Accept: application/vnd.aireceptionist.v1+json`
- `GlobalExceptionHandler` — uniform `ApiResponse{success, errorCode, message, details, timestamp}` envelope
- Exception hierarchy: `AiReceptionistException` → `AuthorizationException`, `BusinessRuleException`, `ExternalServiceException`, `NotFoundException`, `ValidationException`
- OpenAPI / Swagger UI available at `/swagger-ui.html`

### Developer tooling
- `AbstractIntegrationTest` — spins up PostgreSQL + Redis via Testcontainers
- `AbstractModuleTest` — base for `@ApplicationModuleTest` boundary checks
- `docker-compose.yml` — PostgreSQL + Redis for local development
- `AsyncConfig`, `ClockConfig` — injectable `Clock` for testability

---

## Epic 2 — Tenant Registration & Business Onboarding (Complete)

A business owner can register, verify via OTP, complete the 5-question wizard, connect their WhatsApp number, and upload a price list photo. All flows are implemented and tested.

### Tenant lifecycle (hexagonal, Tenant module)
```
POST /api/v1/tenants          → TenantRegistrationService.registerTenant()
POST /api/v1/tenants/verify-otp   → TenantRegistrationService.verifyOtp()  → returns JWT
POST /api/v1/tenants/resend-otp   → TenantRegistrationService.requestNewOtp()
POST /api/v1/tenants/{id}/onboarding   → TenantOnboardingService.completeOnboarding()
POST /api/v1/tenants/{id}/whatsapp     → TenantWhatsAppConnectionService.connectWhatsApp()
DELETE /api/v1/tenants/{id}/data       → TenantDataRightsService.eraseTenantData()
GET    /api/v1/tenants/{id}/data/export → TenantDataRightsService.exportTenantData()
```

**Domain rules enforced:**
- `TenantRegistrationPolicy.ensureDedicatedBusinessPhone()` — rejects registration if owner phone and business phone are the same (FR2)
- Tenant must be `ACTIVE` or `ONBOARDING_COMPLETE` to run the wizard (FR3)
- WhatsApp connection allowed only after `ONBOARDING_COMPLETE` (FR5)
- Duplicate `phoneNumberId` across tenants rejected (FR5)
- Email uniqueness enforced before saving

**Ports implemented (all adapters wired):**
| Port | Adapter |
|------|---------|
| `OtpPort` | `RedisOtpAdapter` — generates, stores (5-min TTL), verifies, invalidates |
| `OwnerNotificationPort` | `WhatsAppOwnerNotificationAdapter` |
| `TokenIssuerPort` | `JwtTokenIssuerAdapter` |
| `SubscriptionProvisioningPort` | `JdbcSubscriptionProvisioningAdapter` |
| `TenantRegistrationRepository` | `TenantPersistenceAdapter` → `JpaTenantRepository` (JPA) |
| `TenantDataStorePort` | `JdbcTenantDataStoreAdapter` |
| `TenantAuditPort` | `AuditLogTenantAuditAdapter` |

**Events:** `TenantOnboardedEvent` published after onboarding; `InboundWhatsAppMessageEvent` published by webhook (consumed by logger only — see Epic 3)

### Knowledge Base (5-question wizard + OCR)
```
POST /api/v1/tenants/{id}/knowledge/ocr/import   → async image extract → parseProductPrices()
POST /api/v1/tenants/{id}/knowledge/ocr/confirm  → KnowledgeBaseService.bulkUpsertOcrProducts()
GET  /api/v1/tenants/{id}/knowledge              → KnowledgeBaseService.findByTenantId()
```

**OCR pipeline:**
- `OcrIngestionService.extractFromImage()` — `@Async`, delegates to pluggable `OcrProvider`
- Two providers: `GoogleVisionOcrProvider` (default), `TesseractOcrProvider` — switchable via `app.ocr.provider`
- `parseProductPrices()` — regex `^(.+?)[\s\-–:]+(?:₹|Rs\.?\s*)?(\d[\d,]*(?:\.\d{1,2})?)(?:/-)?$` — handles Indian price formats
- Throws `OcrLowConfidenceException` if fewer than 2 products parsed (triggers confirm flow)
- KB operations: `bulkUpsertProducts` (wizard source), `bulkUpsertOcrProducts` (OCR source), `bulkUpsertFaqs` — all with upsert + dedup + orphan deletion
- `KnowledgeEntryAddedEvent` fired after every KB write (ready to drive 60s propagation)
- `KnowledgeEntry` domain object: typed (`PRODUCT` / `FAQ`), sourced (`WIZARD` / `OCR`), tenant-scoped

**Tests:** `OcrIngestionServiceTest`, `OcrImportTest`, `KnowledgeBaseServiceTest`, `KnowledgeBaseControllerTest`, `TenantRegistrationTest`, `TenantOnboardingTest`, `OnboardingStatusGuardTest`, `TenantServiceEraseTest`, `TenantRlsTest`, `OtpServiceTest`, `WhatsAppConnectionServiceTest`, `WhatsAppConnectionTest`

---

## Epic 3 — AI WhatsApp Customer Service & Knowledge Base (Not started)

### What is wired (infrastructure only)
- `WhatsAppWebhookController` — receives Meta webhooks at `POST /webhooks/whatsapp`; verifies HMAC-SHA256 signature; resolves tenant from `phoneNumberId`; fires `InboundWhatsAppMessageEvent`
- `WhatsAppWebhookController` — `GET /webhooks/whatsapp` webhook verification (hub.challenge handshake)
- `InboundWhatsAppMessageLogger` — `@ApplicationModuleListener` — logs inbound events, does nothing else
- `LlmService.generateResponse()` — **stub** — throws `ExternalServiceException("LLM not yet configured")`; circuit breaker and confidence gate aspect are wired and ready

### What is not implemented (FR8–FR21)
- LLM integration (OpenAI GPT-4o or Claude) with knowledge base context injection
- Language detection (LangDetect / Google Language API) for first message
- Hinglish (mixed Hindi-English) response handling
- WhatsApp command parsing — distinguishing owner management commands from customer messages by sender phone
- Owner knowledge base commands: add / update / delete entries via WhatsApp
- Conflict detection in KB (FR10)
- Unanswered query flagging to owner WhatsApp (FR11)
- Owner WhatsApp reply → permanent KB addition (FR12)
- 60-second KB propagation to live AI (FR13) — event infrastructure exists; consumer not written
- AI response pipeline: query → KB lookup → LLM → confidence gate → WhatsApp reply
- Frustration/empathy mode detection (FR20: "cheated", "refund", "fraud", ALL CAPS, etc.)
- Lead intent capture from WhatsApp messages (FR21)
- Response caching integration (cache infrastructure exists, not called from AI pipeline)
- WhatsApp outage queue drainer `@Scheduled` processor

### Next story to implement
**Story 3.1** — wire `InboundWhatsAppMessageEvent` into an `AiWhatsAppResponseService` that: (1) looks up tenant KB, (2) calls `LlmService.generateResponse()` with KB context, (3) sends WhatsApp reply via outbound port. The confidence gate aspect and circuit breaker activate automatically.

---

## Epic 4 — Lead Management (Not started)

- `LeadsModule.java` — module marker only, no code
- Database table (`leads`) with consent fields exists from V3 migration
- FR28–FR31 not implemented: no lead entity, no repository, no service, no controller, no morning summary

---

## Epic 5 — Platform Administration & Compliance (Partial)

### Done
- Audit log infrastructure: `AuditLogEntry`, `AuditLogRepository`, `AuditLogWriter`, `AuditLogCleanupJob` (90-day retention)
- DPDP data rights: `TenantDataRightsService.eraseTenantData()` and `exportTenantData()` (FR43, FR44)
- Audit trail on all AI responses via `AiConfidenceGateAspect` (FR45)
- `admin_users` table exists (V7 migration)

### Not implemented
- `AdminModule.java` — module marker only, no code
- No admin controllers (FR37–FR40): no tenant list, no conversation log viewer, no suspend/reactivate, no admin-to-owner WhatsApp notification
- No tenant data isolation enforcement admin view (FR41 — exists at DB layer via RLS)
- No admin access audit trail (NFR13 — RLS and audit log ready, admin access logging not wired)
- No GDPR/DPDP consent capture at lead creation (FR42 — `leads` schema has `consent_timestamp` column, but lead entity doesn't exist yet)

---

## Epics 6, 7, 8 — Not started

- **Epic 6 (Voice):** `VoiceModule.java` is a stub. No Exotel integration, no voice call handling.
- **Epic 7 (Billing):** `BillingModule.java` is a stub. No Razorpay integration, no tier enforcement, no suspension flow.
- **Epic 8 (Franchise):** Not started; no design work begun.

---

## Database State

13 Flyway migrations are applied. All tables for planned features exist:
`tenants`, `knowledge_entries`, `leads`, `subscriptions`, `whatsapp_messages`, `voice_calls`, `admin_users`, `audit_log` + RLS policies + Modulith event tables.

The schema is ahead of the application code — tables for Epic 3 (whatsapp_messages), Epic 4 (leads), Epic 5 (admin_users), Epic 6 (voice_calls), and Epic 7 (subscriptions) exist but the application code for those features is not written yet.

---

## Key Files Reference

| Area | File |
|------|------|
| Entry point | `AiReceptionistApplication.java` |
| Security config | `config/SecurityConfig.java` |
| JWT | `common/security/JwtTokenProvider.java`, `JwtAuthenticationFilter.java` |
| AI confidence gate | `common/ai/AiConfidenceGateAspect.java` |
| LLM (stub) | `common/ai/LlmService.java` |
| Tenant registration | `tenant/application/TenantRegistrationService.java` |
| Tenant onboarding | `tenant/application/TenantOnboardingService.java` |
| WhatsApp connection | `tenant/application/TenantWhatsAppConnectionService.java` |
| Data rights (DPDP) | `tenant/application/TenantDataRightsService.java` |
| WhatsApp webhook | `whatsapp/adapter/in/web/WhatsAppWebhookController.java` |
| WhatsApp message logger | `whatsapp/application/InboundWhatsAppMessageLogger.java` |
| OCR pipeline | `knowledgebase/service/OcrIngestionService.java` |
| Knowledge base service | `knowledgebase/service/KnowledgeBaseService.java` |
| Audit log cleanup | `common/audit/AuditLogCleanupJob.java` |
| Rate limiting | `common/ratelimit/RateLimitFilter.java`, `TenantRateLimitService.java` |
| Response cache | `common/cache/ResponseCacheService.java` |
| WhatsApp queue | `common/queue/WhatsAppQueueService.java` |
| Architecture rules | `docs/hexagonal-architecture.md` |
