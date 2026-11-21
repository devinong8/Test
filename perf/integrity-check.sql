-- Run after perf/loadtest.py (PostgreSQL):  psql -d appointments -f perf/integrity-check.sql
-- Every count except the first two must be 0.
select 'booked appointments' as check, count(*) from appointment where status = 'BOOKED'
union all select 'slots in BOOKED state', count(*) from time_slot where status = 'BOOKED'
union all select 'slots with >1 active booking', count(*)
  from (select slot_id from appointment where status = 'BOOKED' group by slot_id having count(*) > 1) x
union all select 'slot status disagrees with its bookings', count(*)
  from time_slot s full join (select slot_id from appointment where status = 'BOOKED') a on a.slot_id = s.id
 where (s.status = 'BOOKED') <> (a.slot_id is not null)
union all select 'customers with overlapping bookings', count(*)
  from appointment a
  join appointment b on a.customer_id = b.customer_id and a.id < b.id and a.status = 'BOOKED' and b.status = 'BOOKED'
  join time_slot sa on sa.id = a.slot_id
  join time_slot sb on sb.id = b.slot_id
 where sa.start_time < sb.end_time and sb.start_time < sa.end_time;
