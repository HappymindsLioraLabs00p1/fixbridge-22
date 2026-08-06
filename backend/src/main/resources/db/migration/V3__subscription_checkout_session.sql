-- Link a Stripe Checkout session to a pending subscription so the webhook can activate it.
ALTER TABLE subscriptions ADD COLUMN checkout_session text;
CREATE INDEX idx_subscriptions_checkout_session ON subscriptions(checkout_session);
CREATE INDEX idx_subscriptions_stripe_sub ON subscriptions(stripe_subscription_id);
CREATE INDEX idx_subscriptions_user ON subscriptions(user_id);
