# AI Receptionist SaaS — Backend

A multi-tenant AI receptionist backend built with **Spring Boot 3**, **PostgreSQL**, and an **OpenAI** integration placeholder. Each business (tenant) gets its own knowledge base, lead pipeline, and appointment calendar managed through a single modular monolith.

---

## Tech Stack

| Layer        | Technology                         |
|--------------|------------------------------------|
| Language     | Java 17                            |
| Framework    | Spring Boot 3.2                    |
| Build        | Maven                              |
| Database     | PostgreSQL 16                      |
| ORM          | Spring Data JPA / Hibernate        |
| Security     | Spring Security (JWT-ready)        |
| API Docs     | Springdoc OpenAPI / Swagger UI     |
| AI           | OpenAI GPT (placeholder-safe)      |
| Containers   | Docker / Docker Compose            |

---

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### 1. Start PostgreSQL only

```bash
docker compose up postgres -d
```

### 2. Run the backend locally

```bash
cd backend
mvn spring-boot:run
```

The API is available at `http://localhost:8080`.  
Swagger UI: `http://localhost:8080/swagger-ui.html`

### 3. Run everything with Docker Compose

```bash
# Optional: set your real OpenAI key
export OPENAI_API_KEY=sk-...

docker compose up --build
```

---

## Environment Variables

| Variable              | Default           | Description                     |
|-----------------------|-------------------|---------------------------------|
| `OPENAI_API_KEY`      | `placeholder-key` | OpenAI API key                  |
| `SPRING_DATASOURCE_URL` | (see application.yml) | JDBC URL                  |
| `SPRING_DATASOURCE_USERNAME` | `aireceptionist` | DB username             |
| `SPRING_DATASOURCE_PASSWORD` | `aireceptionist_pass` | DB password         |

> When `OPENAI_API_KEY` is left as the placeholder the system returns clearly-labelled mock responses so all endpoints remain functional without a paid key.

### Existing DB Upgrade Note (Knowledge Base refactor)

If you upgraded from an older build and see startup errors like:
`ERROR: column "language" of relation "knowledge_base" contains null values`,
it means legacy `knowledge_base` rows still have nulls in new KB columns.

This project now includes startup backfill SQL in `data.sql` to auto-populate missing `language`, `industry`, and `intent` values before default seeds are inserted. If your DB is still inconsistent, run:

```sql
UPDATE knowledge_base SET language = 'English' WHERE language IS NULL;
UPDATE knowledge_base SET industry = 'CLINIC' WHERE industry IS NULL;
UPDATE knowledge_base SET intent = 'SERVICES' WHERE intent IS NULL;
```

---

## API Reference

### Tenants

```bash
# Create tenant
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "name": "City Dental Clinic",
    "industry": "Healthcare",
    "phone": "+1-555-0200",
    "email": "info@citydental.com",
    "address": "456 Oak Ave, Springfield",
    "workingHours": "Mon-Sat 9am-7pm",
    "defaultLanguage": "English",
    "supportedLanguages": "English,Spanish"
  }'

# List tenants
curl http://localhost:8080/api/tenants

# Get by ID
curl http://localhost:8080/api/tenants/1

# Update
curl -X PUT http://localhost:8080/api/tenants/1 \
  -H "Content-Type: application/json" \
  -d '{"name": "City Dental Clinic Updated", "industry": "Healthcare"}'

# Delete
curl -X DELETE http://localhost:8080/api/tenants/1
```

### Knowledge Base

```bash
# Create tenant-specific knowledge entry (new endpoint)
curl -X POST http://localhost:8080/api/knowledge-base \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "industry": "CLINIC",
    "category": "Hours",
    "intent": "HOURS",
    "question": "Are you open on Sunday?",
    "answer": "Please share your preferred date and time. Our team will confirm slot availability.",
    "language": "English",
    "altQuestions": ["Sunday open?", "Weekend timings"],
    "keywords": ["hours", "timings", "open"],
    "priority": 8,
    "active": true
  }'

# Existing endpoint still works (tenant-scoped create)
curl -X POST http://localhost:8080/api/tenants/1/knowledge \
  -H "Content-Type: application/json" \
  -d '{
    "industry": "CLINIC",
    "category": "Services",
    "intent": "SERVICES",
    "question": "Do you offer dental cleaning?",
    "answer": "Please share your preferred date and phone number. Our team will call you with service details.",
    "language": "English",
    "altQuestions": ["Teeth cleaning available?"],
    "keywords": ["cleaning", "dental"],
    "priority": 7,
    "active": true
  }'

# List knowledge for tenant (new + existing)
curl http://localhost:8080/api/knowledge-base/tenant/1
curl http://localhost:8080/api/tenants/1/knowledge

# List default active entries by industry
curl http://localhost:8080/api/knowledge-base/industry/CLINIC

# Seed default multilingual entries for all industries
curl -X POST http://localhost:8080/api/knowledge-base/seed-defaults

# Update entry
curl -X PUT http://localhost:8080/api/knowledge/1 \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "industry": "CLINIC",
    "category": "Hours",
    "intent": "HOURS",
    "question": "Are you open on Sunday?",
    "answer": "Please share your preferred date/time and phone number. Our team will confirm availability.",
    "language": "English",
    "altQuestions": ["Sunday open?"],
    "keywords": ["hours", "open"],
    "priority": 9,
    "active": true
  }'

# Delete entry
curl -X DELETE http://localhost:8080/api/knowledge/1
```

### Leads

```bash
# Create lead
curl -X POST http://localhost:8080/api/leads \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "customerName": "Jane Smith",
    "phone": "+1-555-0301",
    "email": "jane@example.com",
    "requirement": "Teeth cleaning",
    "source": "MANUAL"
  }'

# List leads for tenant
curl http://localhost:8080/api/tenants/1/leads

# Get lead
curl http://localhost:8080/api/leads/1

# Update lead status
curl -X PUT http://localhost:8080/api/leads/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "CONTACTED"}'
```

### Appointments

```bash
# Book appointment
curl -X POST http://localhost:8080/api/appointments \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "customerName": "John Doe",
    "phone": "+1-555-0401",
    "serviceName": "General Checkup",
    "appointmentTime": "2026-05-10T10:30:00",
    "notes": "First visit"
  }'

# List appointments for tenant
curl http://localhost:8080/api/tenants/1/appointments

# Get appointment
curl http://localhost:8080/api/appointments/1

# Update appointment status
curl -X PUT http://localhost:8080/api/appointments/1/status \
  -H "Content-Type: application/json" \
  -d '{"status": "CONFIRMED"}'
```

### Chat (AI Receptionist)

```bash
# General query
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "customerPhone": "9999999999",
    "message": "What are your working hours?",
    "channel": "CHAT"
  }'

# Appointment booking intent — auto-creates a lead
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": 1,
    "customerPhone": "9999999999",
    "message": "I want to book an appointment tomorrow",
    "channel": "WHATSAPP"
  }'
```

**Chat Response shape:**

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "reply": "I'd be happy to help you book an appointment! ...",
    "intent": "APPOINTMENT_BOOKING",
    "leadCreated": true,
    "appointmentCreated": false
  }
}
```


### Auth (JWT-ready)

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Admin User",
    "email": "admin@citydental.com",
    "password": "SecurePass1",
    "tenantId": 1
  }'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@citydental.com", "password": "SecurePass1"}'
```

---

## Project Structure

```
ai-receptionist/
├── backend/
│   ├── src/main/java/com/aireceptionist/
│   │   ├── AiReceptionistApplication.java
│   │   ├── config/          # Security, OpenAI, CORS, Swagger
│   │   ├── common/          # ApiResponse, exceptions, utils
│   │   ├── auth/            # Registration & login (JWT-ready)
│   │   ├── tenant/          # Multi-tenant management
│   │   ├── knowledge/       # FAQ / service / policy knowledge base
│   │   ├── chat/            # AI chat endpoint + history
│   │   ├── lead/            # Lead capture and pipeline
│   │   ├── appointment/     # Appointment booking
│   │   └── integration/
│   │       ├── openai/      # GPT integration (mock-safe)
│   │       ├── whatsapp/    # WhatsApp stub
│   │       └── calendar/    # Calendar stub
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── data.sql         # Demo seed data
│   ├── pom.xml
│   └── Dockerfile
├── docker-compose.yml
└── README.md
```

---

## Intents Detected by Chat Service

| Intent               | Trigger keywords                                   |
|----------------------|----------------------------------------------------|
| `APPOINTMENT_BOOKING`| appointment, book, schedule, reserve, slot, visit  |
| `PRICE_QUERY`        | price, cost, fee, charge, rate, how much, quote    |
| `HUMAN_HANDOFF`      | human, agent, person, staff, manager, speak to     |
| `GENERAL_QUERY`      | what, how, when, where, who, do you, tell me       |
| `UNKNOWN`            | everything else                                    |

When intent is `APPOINTMENT_BOOKING` or `HUMAN_HANDOFF`, a **lead is automatically created**.

---

## Enabling Real OpenAI

1. Set `OPENAI_API_KEY` to your actual key (env var or `application.yml`).
2. The `OpenAIServiceImpl` will automatically switch from mock to real GPT calls — no code changes needed.

---

## Roadmap

- [ ] JWT authentication (add `jjwt` dependency + filter)
- [ ] Role-based access control per tenant
- [ ] WhatsApp webhook receiver
- [ ] Google Calendar / Calendly integration
- [ ] React frontend dashboard
- [ ] Analytics module (chat volume, lead conversion rate)
- [ ] Multi-language AI prompt templates

## Frontend Test UI (React + Vite)

A simple frontend test app is available in `frontend/`.

```bash
cd frontend
npm install
npm run dev
```

Frontend runs on Vite default `http://localhost:5173` and targets backend base URL:

- `http://localhost:8080/api`

Included pages:

- Login (JWT token stored in localStorage)
- Dashboard
- Knowledge Base (create/list FAQs by tenant)
- Chat Test (POST `/api/chat`)
- Leads (list by tenant)
