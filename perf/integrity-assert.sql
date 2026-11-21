-- Fails (non-zero exit with psql -v ON_ERROR_STOP=1) if the database breaks any booking invariant.
-- Used by CI after the load test:  psql -v ON_ERROR_STOP=1 -d appointments -f perf/integrity-assert.sql
DO $$
DECLARE
  double_booked int; out_of_step int; overlapping int; booked_appts int; booked_slots int;
BEGIN
  SELECT count(*) INTO booked_appts FROM appointment WHERE status = 'BOOKED';
  SELECT count(*) INTO booked_slots FROM time_slot WHERE status = 'BOOKED';
  SELECT count(*) INTO double_booked FROM (
    SELECT slot_id FROM appointment WHERE status = 'BOOKED' GROUP BY slot_id HAVING count(*) > 1) x;
  SELECT count(*) INTO out_of_step
    FROM time_slot s FULL JOIN (SELECT slot_id FROM appointment WHERE status = 'BOOKED') a ON a.slot_id = s.id
   WHERE (s.status = 'BOOKED') <> (a.slot_id IS NOT NULL);
  SELECT count(*) INTO overlapping
    FROM appointment a
    JOIN appointment b ON a.customer_id = b.customer_id AND a.id < b.id AND a.status = 'BOOKED' AND b.status = 'BOOKED'
    JOIN time_slot sa ON sa.id = a.slot_id
    JOIN time_slot sb ON sb.id = b.slot_id
   WHERE sa.start_time < sb.end_time AND sb.start_time < sa.end_time;
  RAISE NOTICE 'booked appointments=%, booked slots=%, double-booked slots=%, out-of-step slots=%, overlapping customer bookings=%',
    booked_appts, booked_slots, double_booked, out_of_step, overlapping;
  IF double_booked > 0 OR out_of_step > 0 OR overlapping > 0 OR booked_appts <> booked_slots THEN
    RAISE EXCEPTION 'Integrity check FAILED';
  END IF;
  RAISE NOTICE 'Integrity check passed';
END $$;
