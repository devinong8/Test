#!/usr/bin/env bash
# Black-box acceptance check: exercises every acceptance criterion of the BE1 brief over real HTTP
# against a running server and prints PASS/FAIL per requirement.
#
#   mvn spring-boot:run          # in another terminal
#   ./acceptance-check.sh        # or: BASE_URL=http://host:8080 ./acceptance-check.sh
#   VERBOSE=1 ./acceptance-check.sh   # also print every response body
#   RECORD=run.tsv ./acceptance-check.sh   # also record every request/response and check result
#
# Needs only bash + curl. Creates its own providers/customers so it can be run repeatedly.
set -u
BASE=${BASE_URL:-http://localhost:8080}
U=$BASE/api/v1
H='Content-Type: application/json'
D=$(date -d "+40 days" +%F 2>/dev/null || date -v+40d +%F)
D2=$(date -d "+41 days" +%F 2>/dev/null || date -v+41d +%F)
CAT="ACC_$RANDOM$RANDOM"
PASS=0; FAIL=0
G='\033[32m'; R='\033[31m'; B='\033[1;36m'; N='\033[0m'

section() { printf "\n${B}%s${N}\n" "$1"; }
# req METHOD PATH [BODY] -> sets CODE and BODY
req() {
  local out
  if [ $# -ge 3 ]; then out=$(curl -s -X "$1" -H "$H" -d "$3" -w $'\n%{http_code}' "$U$2")
  else out=$(curl -s -X "$1" -w $'\n%{http_code}' "$U$2"); fi
  CODE=${out##*$'\n'}; BODY=${out%$'\n'*}
  [ -n "${VERBOSE:-}" ] && printf '      %s %s -> %s %s\n' "$1" "$2" "$CODE" "$BODY"
  [ -n "${RECORD:-}" ] && printf 'REQ\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$(printf '%s' "${3:-}" | base64 | tr -d '\n')" "$CODE" "$(printf '%s' "$BODY" | base64 | tr -d '\n')" >>"$RECORD"
  return 0
}
# check ID DESCRIPTION EXPECTED_CODE [must-contain...]
check() {
  local id=$1 desc=$2 want=$3; shift 3
  local ok=1
  [ "$CODE" = "$want" ] || ok=0
  for needle in "$@"; do case "$BODY" in *"$needle"*) ;; *) ok=0;; esac; done
  [ -n "${RECORD:-}" ] && printf 'CHECK\t%s\t%s\t%s\t%s\t%s\n' "$id" "$desc" "$want" "$CODE" "$ok" >>"$RECORD"
  if [ $ok = 1 ]; then PASS=$((PASS+1)); printf "  ${G}PASS${N} %-6s %-62s [%s]\n" "$id" "$desc" "$CODE"
  else FAIL=$((FAIL+1)); printf "  ${R}FAIL${N} %-6s %-62s [got %s, want %s %s]\n      %s\n" "$id" "$desc" "$CODE" "$want" "$*" "${BODY:0:300}"; fi
}
id_of() { sed -n 's/^{"id":\([0-9]*\).*/\1/p' <<<"$BODY"; }
slot_json() { printf '{"date":"%s","startTime":"%s","endTime":"%s"}' "$1" "$2" "$3"; }

curl -s -o /dev/null "$BASE/actuator/health" || { echo "Server not reachable at $BASE"; exit 2; }
echo "Acceptance check against $BASE  (slot date $D, category $CAT)"

section "Setup"
req POST /providers "{\"name\":\"Acceptance Legal\",\"serviceCategory\":\"$CAT\"}"; P1=$(id_of); check S.1 "Register provider" 201
req POST /providers "{\"name\":\"Acceptance Tax\",\"serviceCategory\":\"${CAT}_TAX\"}"; P2=$(id_of); check S.2 "Register second provider (other category)" 201
req POST /customers "{\"name\":\"Ann\",\"email\":\"ann.$CAT@example.com\"}"; C1=$(id_of); check S.3 "Register customer 1" 201
req POST /customers "{\"name\":\"Ben\",\"email\":\"ben.$CAT@example.com\"}"; C2=$(id_of); check S.4 "Register customer 2" 201

section "R1  Providers define & manage slots; non-overlap; initial AVAILABLE"
req POST /providers/$P1/slots "$(slot_json $D 09:00 10:00)"; S1=$(id_of)
check R1.1 "Create slot (provider, date, start, end) -> AVAILABLE" 201 '"status":"AVAILABLE"' "\"providerId\":$P1"
req GET /providers/$P1/slots/$S1; check R1.2 "Slot is persisted and retrievable" 200 '"startTime":"09:00"'
req POST /providers/$P1/slots "$(slot_json $D 09:30 10:30)"; check R1.3 "Partially overlapping slot rejected" 409 SLOT_OVERLAP
req POST /providers/$P1/slots "$(slot_json $D 08:00 12:00)"; check R1.4 "Enclosing slot rejected" 409 SLOT_OVERLAP
req POST /providers/$P1/slots "$(slot_json $D 10:00 11:00)"; S2=$(id_of); check R1.5 "Back-to-back slot allowed" 201
req POST /providers/$P2/slots "$(slot_json $D 09:00 10:00)"; S3=$(id_of); check R1.6 "Same time, different provider allowed" 201
req POST /providers/$P1/slots "$(slot_json $D 12:00 11:00)"; check R1.7 "End before start -> 400" 400 INVALID_TIME_RANGE
req POST /providers/$P1/slots '{"date":"2020-01-01","startTime":"09:00","endTime":"10:00"}'; check R1.8 "Slot in the past -> 400" 400 INVALID_TIME_RANGE
req POST /providers/999999/slots "$(slot_json $D 09:00 10:00)"; check R1.9 "Unknown provider -> 404" 404 PROVIDER_NOT_FOUND
req POST /providers/$P1/slots/batch "{\"slots\":[$(slot_json $D 13:00 14:00),$(slot_json $D 13:30 14:30)]}"; check R1.10 "Batch with internal overlap rejected (all-or-nothing)" 409 SLOT_OVERLAP
req POST /providers/$P1/slots/batch "{\"slots\":[$(slot_json $D 13:00 14:00),$(slot_json $D 14:00 15:00),$(slot_json $D2 09:00 10:00)]}"; check R1.11 "Batch create" 201
req PUT /providers/$P1/slots/$S2 "$(slot_json $D 10:15 11:15)"; check R1.12 "Reschedule unbooked slot" 200 '"startTime":"10:15"'
req PUT /providers/$P1/slots/$S2 "$(slot_json $D 09:30 10:30)"; check R1.13 "Reschedule onto another slot rejected" 409 SLOT_OVERLAP
req POST /providers/$P1/slots "$(slot_json $D 16:00 17:00)"; S4=$(id_of)
req POST /providers/$P1/slots/$S4/lock; check R1.14 "Lock slot" 200 '"status":"LOCKED"'
req POST /providers/$P1/slots "$(slot_json $D 17:00 18:00)"; S5=$(id_of)
req DELETE /providers/$P1/slots/$S5; check R1.15 "Delete unbooked slot" 204

section "R2  Search available slots: filters, pagination, sorting, exclusions"
req GET "/slots/available?serviceCategory=$CAT"; check R2.1 "Filter by service category" 200 "\"serviceCategory\":\"$CAT\"" '"totalElements":5'
req GET "/slots/available?providerId=$P2"; check R2.2 "Filter by provider ID" 200 "\"providerId\":$P2" '"totalElements":1'
req GET "/slots/available?serviceCategory=$CAT&fromDate=$D2&toDate=$D2"; check R2.3 "Filter by date range" 200 '"totalElements":1' "\"date\":\"$D2\""
req GET "/slots/available?serviceCategory=$CAT&size=2&page=1"; check R2.4 "Paginated (page 1, size 2 of 5)" 200 '"page":1' '"size":2' '"totalPages":3'
req GET "/slots/available?serviceCategory=$CAT&sort=startTime,desc&size=1"; check R2.5 "Sorted (startTime desc -> latest first)" 200 "\"date\":\"$D2\""
req GET "/slots/available?serviceCategory=$CAT&size=100"
case "$BODY" in *"\"id\":$S4,"*) CODE=LOCKED-LEAKED;; esac; check R2.6 "LOCKED slot excluded from search" 200
req GET "/slots/available?sort=password"; check R2.7 "Non-whitelisted sort field -> 400" 400
req GET "/slots/available?fromDate=$D2&toDate=$D"; check R2.8 "fromDate after toDate -> 400" 400 INVALID_TIME_RANGE

section "R3  Booking (customer ID + slot ID / time range), robust"
req POST /appointments "{\"customerId\":$C1,\"slotId\":$S1,\"notes\":\"acceptance\"}"; A1=$(id_of)
check R3.1 "Book by slot ID" 201 '"status":"BOOKED"'
req POST /appointments "{\"customerId\":$C2,\"slotId\":$S1}"; check R3.2 "Double booking same slot -> 409 with clear message" 409 SLOT_ALREADY_BOOKED "Slot $S1 is not available (status: BOOKED)"
req GET "/slots/available?serviceCategory=$CAT&size=100"
case "$BODY" in *"\"id\":$S1,"*) CODE=BOOKED-LEAKED;; esac; check R3.3 "BOOKED slot now excluded from search" 200
req POST /appointments "{\"customerId\":$C2,\"providerId\":$P1,\"date\":\"$D\",\"startTime\":\"10:15\",\"endTime\":\"11:15\"}"
check R3.4 "Book by time range (provider + date + start + end)" 201 '"startTime":"10:15"'
req POST /appointments "{\"customerId\":$C1,\"providerId\":$P1,\"date\":\"$D\",\"startTime\":\"11:00\",\"endTime\":\"10:00\"}"; check R3.5 "Booking with invalid time range -> 400" 400 INVALID_TIME_RANGE
req POST /appointments "{\"customerId\":$C1,\"providerId\":$P1,\"date\":\"$D\",\"startTime\":\"19:00\",\"endTime\":\"20:00\"}"; check R3.6 "Time range matching no slot -> 404" 404 SLOT_NOT_FOUND
req POST /appointments "{\"customerId\":$C1,\"slotId\":999999}"; check R3.7 "Non-existent slot -> 404" 404 SLOT_NOT_FOUND
req POST /appointments "{\"customerId\":999999,\"slotId\":$S3}"; check R3.8 "Non-existent customer -> 404" 404 CUSTOMER_NOT_FOUND
req POST /appointments "{\"customerId\":$C1,\"slotId\":$S4}"; check R3.9 "LOCKED slot cannot be booked -> 409" 409 SLOT_NOT_AVAILABLE
req POST /appointments "{\"customerId\":$C1,\"slotId\":$S3}"; check R3.10 "Customer overlapping own booking -> 409" 409 CUSTOMER_DOUBLE_BOOKING
req POST /appointments "{\"slotId\":$S3}"; check R3.11 "Missing customerId -> 400 with field error" 400 '"field":"customerId"'
# Race: 20 different customers fire at the same slot simultaneously.
req POST /providers/$P1/slots "$(slot_json $D2 15:00 16:00)"; RACE_SLOT=$(id_of)
RACERS=()
for i in $(seq 1 20); do req POST /customers "{\"name\":\"R$i\",\"email\":\"r$i.$CAT@example.com\"}"; RACERS+=("$(id_of)"); done
TMP=$(mktemp -d)
for c in "${RACERS[@]}"; do
  curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -d "{\"customerId\":$c,\"slotId\":$RACE_SLOT}" "$U/appointments" >"$TMP/$c" &
done; wait
WINS=$(cat "$TMP"/* | grep -c 201); LOSSES=$(cat "$TMP"/* | grep -c 409); rm -rf "$TMP"
CODE="$WINS/$LOSSES"; BODY=""; check R3.12 "20 parallel bookings of one slot: exactly 1 wins, 19 get 409" "1/19"

section "R4  Cancellation: ownership, eligibility, atomic release, timestamp + reason"
req POST /appointments/$A1/cancel "{\"customerId\":$C2,\"reason\":\"not mine\"}"; check R4.1 "Non-owner cannot cancel" 403 NOT_APPOINTMENT_OWNER
req GET /appointments/$A1; check R4.2 "...and the appointment is untouched" 200 '"status":"BOOKED"'
req POST /appointments/$A1/cancel "{\"customerId\":$C1,\"reason\":\"Schedule conflict\"}"
check R4.3 "Owner cancels -> CANCELLED with timestamp and reason" 200 '"status":"CANCELLED"' '"cancellationReason":"Schedule conflict"' '"cancelledAt":"20'
check R4.4 "...slot released to AVAILABLE in the same transaction" 200 '"slot":{' '"status":"AVAILABLE"'
req GET /providers/$P1/slots/$S1; check R4.5 "Slot is AVAILABLE when read back" 200 '"status":"AVAILABLE"'
req POST /appointments "{\"customerId\":$C2,\"slotId\":$S1}"; check R4.6 "Released slot can be booked again" 201
req POST /appointments/$A1/cancel "{\"customerId\":$C1,\"reason\":\"again\"}"; check R4.7 "Cancel twice -> 409" 409 APPOINTMENT_ALREADY_CANCELLED
req POST /appointments/999999/cancel "{\"customerId\":$C1,\"reason\":\"x\"}"; check R4.8 "Cancel non-existent appointment -> 404" 404 APPOINTMENT_NOT_FOUND
req POST /appointments/$A1/cancel "{\"customerId\":$C1}"; check R4.9 "Cancel without reason -> 400" 400 '"field":"reason"'
req GET "/customers/$C1/appointments?status=CANCELLED"; check R4.10 "Cancelled appointment kept in history" 200 '"totalElements":1'

section "R5  Unified error handling (standard envelope)"
req POST /appointments "{\"customerId\":$C1,\"slotId\":999999}"
check R5.1 "Error envelope has timestamp/status/error/code/message/path" 404 '"timestamp"' '"status":404' '"error":"Not Found"' '"code":' '"message":' '"path":"/api/v1/appointments"'
req POST /appointments '{not json'; check R5.2 "Malformed JSON -> 400" 400 INVALID_REQUEST
req POST /appointments "{\"customerId\":$C1,\"slotId\":$S3,\"colour\":\"red\"}"; check R5.3 "Unknown JSON field -> 400" 400 colour
req GET /appointments/abc; check R5.4 "Wrong path type -> 400" 400 INVALID_REQUEST
req GET /does-not-exist; check R5.5 "Unknown endpoint -> 404 (same envelope)" 404 RESOURCE_NOT_FOUND

section "Documentation"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs"); BODY=""; check D.1 "OpenAPI spec served at /v3/api-docs" 200
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/swagger-ui/index.html"); check D.2 "Swagger UI served at /swagger-ui.html" 200

printf "\n${B}Result: %d passed, %d failed${N}\n" "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
