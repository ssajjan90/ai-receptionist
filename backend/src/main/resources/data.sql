-- Backfill legacy rows so schema changes remain startup-safe on existing databases.
UPDATE knowledge_base
SET language = 'English'
WHERE language IS NULL;

UPDATE knowledge_base kb
SET industry = CASE
    WHEN t.industry IS NULL OR trim(t.industry) = '' THEN 'CLINIC'
    WHEN upper(replace(replace(t.industry, ' ', '_'), '-', '_')) IN ('CLINIC', 'HOTEL', 'SALON', 'MOBILE_SHOP')
        THEN upper(replace(replace(t.industry, ' ', '_'), '-', '_'))
    WHEN upper(t.industry) IN ('HEALTHCARE', 'HOSPITAL', 'DENTAL') THEN 'CLINIC'
    ELSE 'CLINIC'
END
FROM tenants t
WHERE kb.tenant_id = t.id
  AND kb.industry IS NULL;

UPDATE knowledge_base
SET industry = 'CLINIC'
WHERE industry IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'knowledge_base'
          AND column_name = 'type'
    ) THEN
        EXECUTE $SQL$
            UPDATE knowledge_base
            SET type = 'SERVICE'
            WHERE type IS NULL
        $SQL$;

        EXECUTE $SQL$
            ALTER TABLE knowledge_base
            ALTER COLUMN type SET DEFAULT 'SERVICE'
        $SQL$;

        EXECUTE $SQL$
            UPDATE knowledge_base
            SET intent = CASE
                WHEN upper(type) = 'POLICY' THEN 'CANCELLATION'
                WHEN upper(type) = 'SERVICE' THEN 'SERVICES'
                ELSE 'HOURS'
            END
            WHERE intent IS NULL
        $SQL$;
    END IF;
END $$;

UPDATE knowledge_base
SET intent = 'SERVICES'
WHERE intent IS NULL;

-- Seed a demo tenant
INSERT INTO tenants (name, industry, phone, email, address, working_hours, default_language, supported_languages, created_at, updated_at)
SELECT 'Demo Clinic', 'CLINIC', '+1-555-0100', 'contact@democlinic.com',
       '123 Main Street, Springfield', 'Mon-Fri 9am-6pm, Sat 10am-2pm',
       'English', 'English,Kannada',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE email = 'contact@democlinic.com');

-- Seed default multilingual knowledge (tenant_id NULL => industry defaults)
INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'CLINIC', 'Hours', 'HOURS', 'What are your working hours?',
       'Our clinic hours vary by service. Please share your preferred date and time, and we will confirm availability.',
       'English', 'When are you open?', 'hours|timings', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'CLINIC' AND language = 'English' AND question = 'What are your working hours?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'CLINIC', 'Pricing', 'PRICE', 'How much does treatment cost?',
       'Pricing depends on the treatment type. Please share the service you need and your phone number; our team will contact you with details.',
       'English', 'What is the consultation fee?', 'price|cost|fee', 9, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'CLINIC' AND language = 'English' AND question = 'How much does treatment cost?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'HOTEL', 'Booking', 'BOOKING', 'Do you have rooms available?',
       'Availability changes by date and room type. Please share check-in/check-out dates and your phone number so our team can assist you.',
       'English', 'Can I book a room?', 'availability|rooms|booking', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'HOTEL' AND language = 'English' AND question = 'Do you have rooms available?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'HOTEL', 'Tariff', 'PRICE', 'What is the room price?',
       'Room rates depend on room category and dates. Please share your travel dates and preferences, and we will get back with options.',
       'English', 'How much per night?', 'price|tariff', 9, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'HOTEL' AND language = 'English' AND question = 'What is the room price?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'SALON', 'Services', 'SERVICES', 'What services do you offer?',
       'We offer multiple salon services. Please tell us the service you are looking for and preferred time, and our team will assist you.',
       'English', 'Do you do hair spa?', 'services|hair|facial', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'SALON' AND language = 'English' AND question = 'What services do you offer?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'SALON', 'Pricing', 'PRICE', 'What are your service charges?',
       'Service charges vary by stylist and package. Please share the service details and phone number for an exact quote.',
       'English', 'How much for haircut?', 'price|charge', 9, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'SALON' AND language = 'English' AND question = 'What are your service charges?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'MOBILE_SHOP', 'Availability', 'AVAILABILITY', 'Is this phone model available?',
       'Stock changes quickly. Please share the model name, variant, and your phone number so we can confirm availability.',
       'English', 'Do you have iPhone in stock?', 'availability|stock|model', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'MOBILE_SHOP' AND language = 'English' AND question = 'Is this phone model available?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'MOBILE_SHOP', 'Price', 'PRICE', 'What is the mobile price?',
       'Pricing varies by model and offers. Please share the exact model and your contact number for the latest quote.',
       'English', 'Best price for Samsung?', 'price|offer', 9, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'MOBILE_SHOP' AND language = 'English' AND question = 'What is the mobile price?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'CLINIC', 'ಸಮಯ', 'HOURS', 'ನಿಮ್ಮ ಕೆಲಸದ ಸಮಯ ಏನು?',
       'ನಮ್ಮ ಕ್ಲಿನಿಕ್ ಸಮಯ ಸೇವೆಯ ಪ್ರಕಾರ ಬದಲಾಗಬಹುದು. ದಯವಿಟ್ಟು ನಿಮಗೆ ಬೇಕಾದ ದಿನ ಮತ್ತು ಸಮಯ ಹಂಚಿಕೊಳ್ಳಿ; ನಾವು ದೃಢೀಕರಿಸುತ್ತೇವೆ.',
       'Kannada', 'ನೀವು ಯಾವಾಗ ತೆರೆಯಿರುತ್ತೀರಿ?', 'ಸಮಯ|ಟೈಮಿಂಗ್', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'CLINIC' AND language = 'Kannada' AND question = 'ನಿಮ್ಮ ಕೆಲಸದ ಸಮಯ ಏನು?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'HOTEL', 'ಬುಕಿಂಗ್', 'BOOKING', 'ಕೊಠಡಿ ಲಭ್ಯವಿದೆಯೆ?',
       'ದಿನಾಂಕ ಮತ್ತು ಕೊಠಡಿ ಪ್ರಕಾರದ ಮೇಲೆ ಲಭ್ಯತೆ ಬದಲಾಗುತ್ತದೆ. ದಯವಿಟ್ಟು check-in/check-out ದಿನಾಂಕಗಳನ್ನು ಹಾಗೂ ನಿಮ್ಮ ಫೋನ್ ಸಂಖ್ಯೆ ಹಂಚಿಕೊಳ್ಳಿ.',
       'Kannada', 'ನಾನು ರೂಂ ಬುಕ್ ಮಾಡಬಹುದೇ?', 'ಲಭ್ಯತೆ|ರೂಂ|ಬುಕಿಂಗ್', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'HOTEL' AND language = 'Kannada' AND question = 'ಕೊಠಡಿ ಲಭ್ಯವಿದೆಯೆ?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'SALON', 'ಸೇವೆಗಳು', 'SERVICES', 'ನೀವು ಯಾವ ಸೇವೆಗಳು ನೀಡುತ್ತೀರಿ?',
       'ನಾವು ಹಲವಾರು ಸಲೂನ್ ಸೇವೆಗಳು ನೀಡುತ್ತೇವೆ. ದಯವಿಟ್ಟು ಬೇಕಾದ ಸೇವೆ ಮತ್ತು ಸಮಯ ತಿಳಿಸಿ; ನಮ್ಮ ತಂಡ ಸಂಪರ್ಕಿಸುತ್ತದೆ.',
       'Kannada', 'ಹೇರ್ ಸ್ಪಾ ಇದೆಯೆ?', 'ಸೇವೆಗಳು|ಹೇರ್|ಫೇಷಿಯಲ್', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'SALON' AND language = 'Kannada' AND question = 'ನೀವು ಯಾವ ಸೇವೆಗಳು ನೀಡುತ್ತೀರಿ?');

INSERT INTO knowledge_base (tenant_id, industry, category, intent, question, answer, language, alt_questions, keywords, priority, active, created_at, updated_at)
SELECT NULL, 'MOBILE_SHOP', 'ಲಭ್ಯತೆ', 'AVAILABILITY', 'ಈ ಫೋನ್ ಮಾದರಿ ಲಭ್ಯವಿದೆಯೆ?',
       'ಸ್ಟಾಕ್ ತ್ವರಿತವಾಗಿ ಬದಲಾಗುತ್ತದೆ. ದಯವಿಟ್ಟು ಮಾದರಿ ಹೆಸರು, ವರ್ಗ, ಮತ್ತು ನಿಮ್ಮ ಫೋನ್ ಸಂಖ್ಯೆ ಹಂಚಿಕೊಳ್ಳಿ; ನಾವು ದೃಢೀಕರಿಸುತ್ತೇವೆ.',
       'Kannada', 'iPhone ಸ್ಟಾಕ್ ಇದೆಯೆ?', 'ಲಭ್ಯತೆ|ಸ್ಟಾಕ್|ಮಾದರಿ', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id IS NULL AND industry = 'MOBILE_SHOP' AND language = 'Kannada' AND question = 'ಈ ಫೋನ್ ಮಾದರಿ ಲಭ್ಯವಿದೆಯೆ?');
