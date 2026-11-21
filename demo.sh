#!/usr/bin/env bash
# Live walkthrough of every acceptance criterion. Start the app first:  mvn spring-boot:run
# Uses a slot date 30 days ahead so it never collides with seeded data.
set -u
U=${BASE_URL:-http://localhost:8080}/api/v1
H='Content-Type: application/json'
D=$(date -d "+30 days" +%F 2>/dev/null || date -v+30d +%F)

step() { printf '\n\033[1;36m== %s\033[0m\n' "$1"; }
call() { curl -s -w '  -> HTTP %{http_code}\n' "$@"; }

step "Register provider and two customers"
P=$(curl -s -H "$H" -d '{"name":"Demo Legal Pte Ltd","serviceCategory":"LEGAL"}' $U/providers | sed 's/.*"id":\([0-9]*\).*/\1/'); echo "provider=$P"
C1=$(curl -s -H "$H" -d "{\"name\":\"Ann\",\"email\":\"ann$RANDOM@ex.com\"}" $U/customers | sed 's/.*"id":\([0-9]*\).*/\1/'); echo "customer1=$C1"
C2=$(curl -s -H "$H" -d "{\"name\":\"Ben\",\"email\":\"ben$RANDOM@ex.com\"}" $U/customers | sed 's/.*"id":\([0-9]*\).*/\1/'); echo "customer2=$C2"

step "1. Create slot 09:00-10:00 (expect 201 AVAILABLE)"
S=$(curl -s -H "$H" -d "{\"date\":\"$D\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"}" $U/providers/$P/slots | sed 's/.*"id":\([0-9]*\),"providerId.*/\1/'); echo "slot=$S"
step "1. Overlapping slot 09:30-10:30 (expect 409 SLOT_OVERLAP)"
call -H "$H" -d "{\"date\":\"$D\",\"startTime\":\"09:30\",\"endTime\":\"10:30\"}" $U/providers/$P/slots
step "1. Back-to-back slot 10:00-11:00 (expect 201)"
call -H "$H" -d "{\"date\":\"$D\",\"startTime\":\"10:00\",\"endTime\":\"11:00\"}" $U/providers/$P/slots
step "1. End before start (expect 400)"
call -H "$H" -d "{\"date\":\"$D\",\"startTime\":\"12:00\",\"endTime\":\"11:00\"}" $U/providers/$P/slots

step "2. Search available LEGAL slots on $D, sorted"
call "$U/slots/available?serviceCategory=legal&providerId=$P&fromDate=$D&toDate=$D&sort=startTime,asc"

step "3. Customer 1 books slot $S (expect 201)"
A=$(curl -s -H "$H" -d "{\"customerId\":$C1,\"slotId\":$S}" $U/appointments | sed 's/^{"id":\([0-9]*\),.*/\1/'); echo "appointment=$A"
step "3. Customer 2 tries the same slot (expect 409 SLOT_ALREADY_BOOKED)"
call -H "$H" -d "{\"customerId\":$C2,\"slotId\":$S}" $U/appointments
step "3. Customer 2 books by time range 10:00-11:00 (expect 201)"
call -H "$H" -d "{\"customerId\":$C2,\"providerId\":$P,\"date\":\"$D\",\"startTime\":\"10:00\",\"endTime\":\"11:00\"}" $U/appointments
step "2. Search again: booked slots are gone"
call "$U/slots/available?providerId=$P&fromDate=$D&toDate=$D"

step "4. Customer 2 tries to cancel customer 1's appointment (expect 403)"
call -H "$H" -d "{\"customerId\":$C2,\"reason\":\"not mine\"}" $U/appointments/$A/cancel
step "4. Customer 1 cancels (expect 200 CANCELLED, slot AVAILABLE)"
call -H "$H" -d "{\"customerId\":$C1,\"reason\":\"Schedule conflict\"}" $U/appointments/$A/cancel
step "4. Cancel again (expect 409)"
call -H "$H" -d "{\"customerId\":$C1,\"reason\":\"again\"}" $U/appointments/$A/cancel

step "5. Errors: 404 and malformed body 400"
call $U/appointments/999999
call -H "$H" -d '{"customerId":"abc","slotId":1}' $U/appointments
