-- Epic 7 (stories 7.1-7.4): subscription tier/usage/billing state.
-- ENTERPRISE tier exists in the PRD (franchise accounts) but was missing from both CHECKs.
ALTER TABLE tenants DROP CONSTRAINT chk_tenants_tier;
ALTER TABLE tenants ADD CONSTRAINT chk_tenants_tier CHECK (tier IN ('BASIC', 'PRO', 'ENTERPRISE'));

ALTER TABLE subscriptions DROP CONSTRAINT chk_subscriptions_tier;
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_tier CHECK (tier IN ('BASIC', 'PRO', 'ENTERPRISE'));

ALTER TABLE subscriptions
    ADD COLUMN conversation_used_this_month INTEGER     NOT NULL DEFAULT 0,
    -- NULL = unlimited (PRO/ENTERPRISE); BASIC = 300/month per the PRD tier table.
    ADD COLUMN conversation_limit           INTEGER,
    ADD COLUMN next_billing_date            TIMESTAMPTZ,
    ADD COLUMN grace_period_ends_at         TIMESTAMPTZ,
    ADD COLUMN suspended_at                 TIMESTAMPTZ,
    ADD COLUMN razorpay_plan_id             VARCHAR(255);

UPDATE subscriptions SET conversation_limit = 300 WHERE tier = 'BASIC';

CREATE UNIQUE INDEX uq_subscriptions_razorpay_subscription_id
    ON subscriptions(razorpay_subscription_id) WHERE razorpay_subscription_id IS NOT NULL;
