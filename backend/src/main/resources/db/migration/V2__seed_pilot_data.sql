-- Pilot seed data. All prices are ADMIN-EDITABLE pilot numbers (per the spec), not permanent
-- constants — they live in data, not code. Amounts are in cents.

-- Trades
INSERT INTO trades (code, name) VALUES
  ('plumbing',   'Plumbing'),
  ('electrical', 'Electrical'),
  ('hvac',       'HVAC'),
  ('handyman',   'Handyman'),
  ('appliance',  'Appliance Repair'),
  ('roofing',    'Roofing'),
  ('carpentry',  'Carpentry'),
  ('painting',   'Painting')
ON CONFLICT (code) DO NOTHING;

-- Suggested pilot Service Assessment & Dispatch pricing (customer price / contractor visit payout)
INSERT INTO dispatch_fees (service_type, customer_price_cents, contractor_visit_cents, active) VALUES
  ('weekday_scheduled',     14900, 10000, true),
  ('same_day_priority',     22900, 15000, true),
  ('evening_weekend',       29900, 20000, true),
  ('commercial_scheduled',  22500, 15000, true),
  ('commercial_emergency',  35000, 22500, true);

-- Default global pricing rule (25% target gross margin, $75 minimum gross profit, 2.9% + $0.30 fees)
INSERT INTO pricing_rules
  (scope, target_gross_margin, minimum_gross_profit_cents, fixed_platform_cost_cents,
   risk_reserve_cents, variable_payment_fee_rate, fixed_payment_fee_cents, location_factor, active)
VALUES
  ('global', 0.2500, 7500, 7500, 5000, 0.0290, 30, 1.000, true);
