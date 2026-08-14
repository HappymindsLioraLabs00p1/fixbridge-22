#!/usr/bin/env bash
#
# Seeds compliant contractors into a STAGING database so the dispatch half of the product
# can be exercised.
#
# Without contractors, matching correctly returns nothing and the quote card has no visit
# fee to show — the flow looks broken when it is in fact behaving. This puts enough real
# state behind it to run a job from report through to rating.
#
# Accounts are created through the live /api/auth/register endpoint rather than by writing
# rows: the password hash and role assignment then come from the same code that runs in
# production, so a seeded contractor can actually log in. Everything after that is SQL,
# because it is state an admin would otherwise click through — document approval, rate
# cards, service geography.
#
# STAGING ONLY. The licence numbers are fabricated and the accounts share one password.
#
#   export DATABASE_URL='postgres://user:pass@host:5432/db?sslmode=require'
#   ./scripts/seed-staging-contractors.sh
#
set -uo pipefail

API="${API:-http://localhost:8080}"
SEED_PASSWORD="${SEED_PASSWORD:-TestPass!2026}"

# Read from the environment, never from this file. A committed connection string is a
# committed password: it stays in git history after deletion, and GitHub's secret scanning
# will disable the database role within minutes of the push.
if [[ -z "${DATABASE_URL:-}" ]]; then
  echo "DATABASE_URL is not set. Export the staging connection string first:" >&2
  echo "  export DATABASE_URL='postgres://user:pass@host:5432/db?sslmode=require'" >&2
  exit 1
fi

case "$DATABASE_URL" in
  *prod*|*production*)
    echo "Refusing to run: DATABASE_URL looks like production." >&2
    exit 1;;
esac

command -v psql >/dev/null || { echo "psql not found on PATH" >&2; exit 1; }

# email|business|city|lat|lon|radius|visit|emergency|afterhours|weekend|cancel|minlabor|trades
# Fees are whole amounts in cents, and each is a TOTAL rather than a surcharge added to the
# standard visit fee — the calculator picks exactly one.
ROWS=(
"kingsway@fixbridge.test|Kingsway Plumbing & Drain|Queens, NY|40.7282|-73.7949|25|8900|18500|14500|12500|4500|15000|plumbing"
"hartline@fixbridge.test|Hartline Electric|Brooklyn, NY|40.6782|-73.9442|20|11000|22500|17500|15000|5000|18000|electrical"
"northshore@fixbridge.test|Northshore Heating & Cooling|Hicksville, NY|40.7684|-73.5251|30|9500|21000|16000|13500|4000|16500|hvac,appliance"
"bedrock@fixbridge.test|Bedrock Home Repair|Manhattan, NY|40.7831|-73.9712|15|7500|16000|13000|11000|3500|12000|handyman,carpentry,painting"
"atlas@fixbridge.test|Atlas Roofing & Exteriors|Yonkers, NY|40.9312|-73.8988|35|12500|24000|19000|16000|6000|22000|roofing"
)

echo "==> registering contractor accounts via $API"
for row in "${ROWS[@]}"; do
  IFS='|' read -r email business _ <<< "$row"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\",\"fullName\":\"$business\",\"role\":\"contractor\"}")
  # 409 means the account is already there, which is fine — this script is re-runnable.
  printf '    %-28s %s\n' "$email" "$code"
done

echo "==> contractor records, rate cards, skills, compliance"
for row in "${ROWS[@]}"; do
  IFS='|' read -r email business city lat lon radius visit emerg after wknd cancel minlab trades <<< "$row"

  psql "$DATABASE_URL" -q <<SQL
with u as (select id from profiles where email = '$email'),
ins as (
  insert into contractors (
    id, owner_user_id, business_name, contact_email, contact_phone, status,
    travel_radius_miles, stripe_account_id, connect_onboarded, payouts_enabled,
    latitude, longitude, service_city,
    visit_fee_cents, emergency_fee_cents, after_hours_fee_cents, weekend_fee_cents,
    cancellation_fee_cents, minimum_labor_cents, created_at, updated_at)
  select gen_random_uuid(), u.id, '$business', '$email', '+1 555 0100', 'approved',
         $radius, 'acct_test_' || substr(replace(u.id::text,'-',''),1,12), true, true,
         $lat, $lon, '$city',
         $visit, $emerg, $after, $wknd, $cancel, $minlab, now(), now()
  from u
  where not exists (select 1 from contractors c where c.owner_user_id = u.id)
  returning id
),
c as (select id from ins union all select c.id from contractors c join u on c.owner_user_id = u.id)
-- A licence and an insurance certificate, both current. Without these the contractor is
-- invisible to dispatch, which is the behaviour we want to be able to test either way.
insert into contractor_documents (id, contractor_id, kind, jurisdiction, number, storage_key, status, expires_on, created_at)
select gen_random_uuid(), c.id, k.kind, 'NY', 'TEST-' || upper(substr(md5(c.id::text || k.kind),1,8)),
       'seed/' || k.kind || '.pdf', 'valid', current_date + interval '18 months', now()
from c cross join (values ('license'),('insurance')) as k(kind)
where not exists (select 1 from contractor_documents d where d.contractor_id=c.id and d.kind=k.kind);
SQL

  # Declared trades, one statement per trade. A WITH clause scopes to a single statement,
  # so a shared CTE cannot be reused across the batch.
  IFS=',' read -ra TL <<< "$trades"
  for i in "${!TL[@]}"; do
    primary=$([ "$i" -eq 0 ] && echo true || echo false)
    psql "$DATABASE_URL" -q -c "
      insert into contractor_skills (id, contractor_id, trade, years, is_primary, created_at)
      select gen_random_uuid(), c.id, '${TL[$i]}', 8, $primary, now()
      from contractors c
      where c.contact_email = '$email'
        and not exists (select 1 from contractor_skills s
                        where s.contractor_id = c.id and s.trade = '${TL[$i]}');"
  done
  printf '    %-30s %-22s $%s visit · %s\n' "$business" "$city" "$((visit/100))" "$trades"
done

# The catalogue counts pros through contractor_skills; contractor_trades is the normalised
# join the rest of the app reads. Keep them in step.
psql "$DATABASE_URL" -q -c "
  insert into contractor_trades (contractor_id, trade_id)
  select s.contractor_id, t.id from contractor_skills s join trades t on t.code = s.trade
  on conflict do nothing;"

echo "==> verifying against the real dispatch gate"
psql "$DATABASE_URL" -tAc "
select rpad(c.business_name,32)
       || rpad(coalesce(string_agg(distinct s.trade,'+'),'NO TRADE'),30)
       || rpad('\$'||(c.visit_fee_cents/100)::text,6)
       || case when count(distinct d.kind) filter (where d.status='valid' and d.expires_on > current_date) = 2
                and c.status = 'approved' and c.payouts_enabled
               then 'DISPATCHABLE' else 'blocked' end
from contractors c
left join contractor_skills s on s.contractor_id = c.id
left join contractor_documents d on d.contractor_id = c.id
group by c.id, c.business_name, c.visit_fee_cents, c.status, c.payouts_enabled
order by c.business_name"
