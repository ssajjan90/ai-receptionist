#!/usr/bin/env node
// Mints a PLATFORM_ADMIN JWT for testing /v1/admin/** endpoints in Postman, curl,
// or the WhatsApp simulator. There is no login endpoint that issues this role (see
// Story 5-1 notes — admin_users exists but nothing authenticates against it yet) —
// this signs a token locally with the same RSA key application-local.yml gives the
// app, exactly matching what JwtTokenProvider.generateToken produces at runtime.
//
// Always mints the SAME admin identity (fixed userId below) rather than a fresh
// random one each run, so this behaves like one stable "super admin account" for
// local testing — e.g. audit_log rows recorded against this admin id stay
// consistent across sessions instead of a new id every time you re-run this.
//
// Usage: node scripts/mint-admin-jwt.js
// Uses only Node's built-in crypto module — no npm install required.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// Fixed, obviously-not-a-real-tenant identity for the one local super-admin account.
const SUPER_ADMIN_ID = '00000000-0000-0000-0000-000000000001';

function readPrivateKeyFromLocalYaml() {
  const yamlPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'application-local.yml');
  const text = fs.readFileSync(yamlPath, 'utf8');
  const lines = text.split('\n');
  const startIdx = lines.findIndex(l => l.trim() === 'private-key: |');
  if (startIdx === -1) throw new Error('private-key block not found in application-local.yml');
  const keyLines = [];
  for (let i = startIdx + 1; i < lines.length; i++) {
    const line = lines[i];
    if (!line.startsWith('      ')) break; // dedent = end of the YAML block scalar
    keyLines.push(line.trim());
  }
  return keyLines.join('\n');
}

function base64url(input) {
  return Buffer.from(input)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

function mintToken({ tenantId, userId, role, tier, expiryMinutes }) {
  const privateKeyPem = readPrivateKeyFromLocalYaml();
  const header = { alg: 'RS256', typ: 'JWT' };
  const nowSeconds = Math.floor(Date.now() / 1000);
  const payload = {
    tenantId,
    userId,
    role,
    tier,
    iat: nowSeconds,
    exp: nowSeconds + expiryMinutes * 60,
  };

  const encodedHeader = base64url(JSON.stringify(header));
  const encodedPayload = base64url(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;

  const signer = crypto.createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  const signature = signer
    .sign(privateKeyPem)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');

  return `${signingInput}.${signature}`;
}

const EXPIRY_MINUTES = 60 * 24 * 30; // 30 days — a local-only test credential, re-mint anytime by re-running this.

const token = mintToken({
  tenantId: SUPER_ADMIN_ID,
  userId: SUPER_ADMIN_ID,
  role: 'PLATFORM_ADMIN',
  tier: 'PRO',
  expiryMinutes: EXPIRY_MINUTES,
});

const expiresAt = new Date(Date.now() + EXPIRY_MINUTES * 60 * 1000);
console.error(`Super admin id: ${SUPER_ADMIN_ID}`);
console.error(`Expires:        ${expiresAt.toLocaleString()}`);
console.error('');
console.error('Example:');
console.error(`  curl -H "Authorization: Bearer $(node scripts/mint-admin-jwt.js 2>/dev/null)" http://localhost:8081/v1/admin/tenants`);
console.error('');
console.log(token);
