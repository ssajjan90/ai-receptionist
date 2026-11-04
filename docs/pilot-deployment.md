# Pilot deployment checklist (one customer, WhatsApp-only)

Scope: PRD Phase 1 — WhatsApp AI receptionist, OCR onboarding, KB, lead capture, owner
commands, admin tools. Voice (Exotel) and billing (Razorpay) stay **off** for the pilot; they
switch themselves off when their secrets are blank.

Verified locally (2026-11-04): the jar boots with `SPRING_PROFILES_ACTIVE=prod` and only the
variables below; health UP, Meta webhook handshake OK, unauthenticated API → 401, unknown URLs →
404, zero ERROR log lines at idle.

## 1. Infrastructure
- [ ] Public **HTTPS** host for the app (Meta webhooks require HTTPS). Run **one instance**
      (scheduled jobs aren't multi-instance safe — W69).
- [ ] Managed **Postgres 16** with automated backups, and **Redis 7**.
- [ ] Two DB roles (W99 — otherwise row-level security is a silent no-op):
      - migration/owner role (runs Flyway) → `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`
      - runtime role, `NOSUPERUSER NOBYPASSRLS`, DML-only → `DB_USERNAME` / `DB_PASSWORD`
      (mirror `docker/postgres-init/01-create-app-runtime-role.sql`).

## 2. Required environment (app refuses to start without these)
| Variable | What |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` | see above |
| `REDIS_HOST`, `REDIS_PORT` | |
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | RSA 2048 PEM pair (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`) |
| `SPRING_AI_OPENAI_API_KEY` | OpenAI key |
| `WHATSAPP_VERIFY_TOKEN` | any random string; also entered in Meta's webhook settings |
| `WHATSAPP_APP_SECRET` | Meta app secret (webhook signature check) |
| `WHATSAPP_ACCESS_TOKEN` | permanent system-user token |
| `WHATSAPP_PLATFORM_PHONE_NUMBER_ID` | phone_number_id of **CallSahayak's own** number (sends owner OTPs) |
| `OTP_WHATSAPP_TEMPLATE` | name of the approved authentication template (e.g. `otp_login`) |
| `OTP_SECRET`, `EXPORT_HASH_SECRET` | random 32+ char secrets |

Optional: `GOOGLE_APPLICATION_CREDENTIALS` (+ `OCR_PROVIDER=google`, the default) for price-list
OCR — or `OCR_PROVIDER=tesseract`. Voice/billing variables: leave unset.

## 3. Meta (WhatsApp Cloud API)
- [ ] Create + get **approved** an *Authentication* template (copy-code button) named as
      `OTP_WHATSAPP_TEMPLATE`, language `en`.
- [ ] Webhook URL `https://<host>/webhooks/whatsapp`, verify token = `WHATSAPP_VERIFY_TOKEN`,
      subscribe to `messages`.
- [ ] Owner alerts (new lead, unanswered question, frustrated customer) are free-form text and
      only deliver inside Meta's 24h window (W70): ask the pilot owner to message the number at
      least daily, or approve utility templates for them.

## 4. Onboard the pilot customer
1. Owner registers (`POST /v1/tenants/register`) → receives the OTP on WhatsApp → verifies.
2. Onboarding (business profile/FAQs, optional OCR price list) → WhatsApp connect
   (`POST /v1/tenants/{id}/whatsapp/connect` with their WABA id / phone_number_id).
3. Billing is off: set the pilot tenant to PRO so the 300/month Basic cap never blocks them:
   `UPDATE tenants SET tier='PRO' WHERE id=…;` and the subscriptions row via the migration role
   (`UPDATE subscriptions SET tier='PRO', conversation_limit=NULL WHERE tenant_id=…;`).
4. Smoke test with a real phone: customer question → AI answer; "I want to buy…" → consent →
   name+phone → lead appears (`GET /v1/tenants/{id}/leads`) and owner gets the alert.
