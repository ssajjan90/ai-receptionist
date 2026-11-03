-- Epic 7 code review fixes.
-- Rows provisioned at registration after V29 got a NULL limit (= unlimited); BASIC is 300/month.
UPDATE subscriptions SET conversation_limit = 300 WHERE tier = 'BASIC' AND conversation_limit IS NULL;

ALTER TABLE subscriptions
    -- Ordering guard: Razorpay event created_at of the last event applied (older events are ignored).
    ADD COLUMN last_event_at                   TIMESTAMPTZ,
    -- A checkout that hasn't been paid yet; promoted to razorpay_subscription_id on its first charge.
    ADD COLUMN pending_razorpay_subscription_id VARCHAR(255),
    ADD COLUMN pending_razorpay_plan_id         VARCHAR(255),
    ADD COLUMN pending_checkout_url             TEXT,
    -- Fixed day-of-month anchor for usage-month renewals (no 31st → 28th drift).
    ADD COLUMN billing_anchor_at                TIMESTAMPTZ;
