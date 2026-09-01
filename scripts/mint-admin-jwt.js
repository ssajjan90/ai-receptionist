#!/usr/bin/env node
// Mints a PLATFORM_ADMIN JWT for testing /v1/admin/** endpoints in Postman.
// There is no login endpoint that issues this role (see Story 5-1 notes) — this
// signs a token locally with the same RSA key application-local.yml gives the app,
// exactly matching what JwtTokenProvider.generateToken produces at runtime.
//
// Usage: node scripts/mint-admin-jwt.js
// Uses only Node's built-in crypto module — no npm install required.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

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

const token = mintToken({
  tenantId: crypto.randomUUID(),
  userId: crypto.randomUUID(),
  role: 'PLATFORM_ADMIN',
  tier: 'PRO',
  expiryMinutes: 120,
});

console.log(token);
