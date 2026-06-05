-- Seed a demo tenant
INSERT INTO tenants (name, industry, phone, email, address, working_hours, default_language, supported_languages, created_at, updated_at)
SELECT 'Demo Clinic', 'Healthcare', '+1-555-0100', 'contact@democlinic.com',
       '123 Main Street, Springfield', 'Mon-Fri 9am-6pm, Sat 10am-2pm',
       'English', 'English,Hindi',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE email = 'contact@democlinic.com');

-- Seed knowledge base for demo tenant
INSERT INTO knowledge_base (tenant_id, type, question, answer, active, created_at, updated_at)
SELECT 1, 'FAQ', 'What are your working hours?',
       'We are open Monday to Friday 9am-6pm and Saturday 10am-2pm.', true,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM tenants WHERE id = 1)
  AND NOT EXISTS (SELECT 1 FROM knowledge_base WHERE question = 'What are your working hours?');

INSERT INTO knowledge_base (tenant_id, type, question, answer, active, created_at, updated_at)
SELECT 1, 'SERVICE', 'What services do you offer?',
       'We offer general consultations, health checkups, and specialist referrals.', true,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM tenants WHERE id = 1)
  AND NOT EXISTS (SELECT 1 FROM knowledge_base WHERE question = 'What services do you offer?');

INSERT INTO knowledge_base (tenant_id, type, question, answer, active, created_at, updated_at)
SELECT 1, 'POLICY', 'What is your cancellation policy?',
       'Please cancel at least 24 hours in advance to avoid a cancellation fee.', true,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 FROM tenants WHERE id = 1)
  AND NOT EXISTS (SELECT 1 FROM knowledge_base WHERE question = 'What is your cancellation policy?');
