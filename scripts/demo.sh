#!/usr/bin/env bash
# End-to-end demo against a running instance (default http://localhost:8080).
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
RUN_ID=$(date +%s)
ALICE="alice-$RUN_ID@example.com"
BOB="bob-$RUN_ID@example.com"
PASS='Passw0rd!23'

access_token() { sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
step() { printf '\n== %s ==\n' "$1"; }
echo "Waiting for $BASE to be healthy..."
for _ in $(seq 1 40); do
  curl -sf "$BASE/actuator/health" > /dev/null && break
  sleep 3
done
curl -sf "$BASE/actuator/health" > /dev/null || { echo "App not reachable at $BASE" >&2; exit 1; }

step "1. Register two users (each gets a wallet)"
ALICE_TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$ALICE\",\"password\":\"$PASS\",\"fullName\":\"Alice\"}" | access_token)
BOB_TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" \
  -d "{\"email\":\"$BOB\",\"password\":\"$PASS\",\"fullName\":\"Bob\"}" | access_token)
echo "registered $ALICE and $BOB"

step "2. Alice tops up 50000 paise (INR 500.00)"
curl -s -X POST "$BASE/api/v1/wallets/topup" -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" -d '{"amount":50000}'; echo

step "3. Alice sends 10000 paise to Bob (Idempotency-Key: demo-$RUN_ID)"
curl -s -X POST "$BASE/api/v1/transfers" -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Idempotency-Key: demo-$RUN_ID" -H "Content-Type: application/json" \
  -d "{\"toEmail\":\"$BOB\",\"amount\":10000}"; echo

step "4. Retry with the SAME key: same transaction id, money moves once"
curl -s -X POST "$BASE/api/v1/transfers" -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Idempotency-Key: demo-$RUN_ID" -H "Content-Type: application/json" \
  -d "{\"toEmail\":\"$BOB\",\"amount\":10000}"; echo

step "5. Same key, different amount: expect 422"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST "$BASE/api/v1/transfers" \
  -H "Authorization: Bearer $ALICE_TOKEN" -H "Idempotency-Key: demo-$RUN_ID" \
  -H "Content-Type: application/json" -d "{\"toEmail\":\"$BOB\",\"amount\":99}"

step "6. Transfer more than Alice has: expect 422 (no overdraft)"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST "$BASE/api/v1/transfers" \
  -H "Authorization: Bearer $ALICE_TOKEN" -H "Idempotency-Key: demo-big-$RUN_ID" \
  -H "Content-Type: application/json" -d "{\"toEmail\":\"$BOB\",\"amount\":9000000}"

step "7. Balances (Alice 40000, Bob 10000)"
printf 'Alice: '; curl -s -H "Authorization: Bearer $ALICE_TOKEN" "$BASE/api/v1/wallets/me"; echo
printf 'Bob:   '; curl -s -H "Authorization: Bearer $BOB_TOKEN" "$BASE/api/v1/wallets/me"; echo

step "8. Bob's ledger (double-entry history)"
curl -s -H "Authorization: Bearer $BOB_TOKEN" "$BASE/api/v1/wallets/me/ledger?size=5"; echo

step "9. Notifications, delivered asynchronously through Kafka"
sleep 3
printf 'Alice: '; curl -s -H "Authorization: Bearer $ALICE_TOKEN" "$BASE/api/v1/notifications"; echo
printf 'Bob:   '; curl -s -H "Authorization: Bearer $BOB_TOKEN" "$BASE/api/v1/notifications"; echo

printf '\nDone.\n'