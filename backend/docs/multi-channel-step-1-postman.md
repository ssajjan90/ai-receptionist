# Step 1 — Tenant Channel Configuration APIs (Postman Examples)

This step introduces channel onboarding APIs so each tenant can independently configure AI receptionist behavior for each communication channel.

## 1) Create channel configuration

**POST** `{{baseUrl}}/api/tenants/{{tenantId}}/channels`

```json
{
  "channel": "WHATSAPP",
  "enabled": true,
  "botDisplayName": "City Dental Assistant",
  "welcomeMessage": "Hi 👋 Welcome to City Dental. How may I help you today?",
  "webhookUrl": "https://hooks.example.com/whatsapp/city-dental",
  "providerConfig": "{\"provider\":\"meta\",\"phoneNumberId\":\"1234567890\"}"
}
```

## 2) List all channel configurations

**GET** `{{baseUrl}}/api/tenants/{{tenantId}}/channels`

## 3) Get one channel configuration

**GET** `{{baseUrl}}/api/tenants/{{tenantId}}/channels/WHATSAPP`

## 4) Update full channel configuration

**PUT** `{{baseUrl}}/api/tenants/{{tenantId}}/channels/WHATSAPP`

```json
{
  "channel": "WHATSAPP",
  "enabled": true,
  "botDisplayName": "City Dental WhatsApp Desk",
  "welcomeMessage": "Hello! Please share your query and preferred appointment time.",
  "webhookUrl": "https://hooks.example.com/whatsapp/city-dental-v2",
  "providerConfig": "{\"provider\":\"meta\",\"phoneNumberId\":\"1234567890\",\"version\":\"v2\"}"
}
```

## 5) Enable/disable a channel quickly

**PATCH** `{{baseUrl}}/api/tenants/{{tenantId}}/channels/WHATSAPP/status`

```json
{
  "enabled": false
}
```

## 6) Delete channel configuration

**DELETE** `{{baseUrl}}/api/tenants/{{tenantId}}/channels/WHATSAPP`
