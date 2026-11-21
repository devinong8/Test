#!/usr/bin/env python3
"""
Load test for the Appointment Management System (standard library only).

    mvn package -DskipTests
    java -jar target/appointment-management-1.0.0.jar --spring.profiles.active=postgres &
    python3 perf/loadtest.py                      # BASE_URL=http://host:8080 to override

Scenarios
  1. Bookings   : 1,000 customers each book a different slot, 50 clients in parallel
  2. Search     : 2,000 available-slot searches with mixed filters, 50 clients in parallel
  3. Hot slot   : 200 customers fire at ONE slot at the same instant -> expect 1 x 201, 199 x 409

Writes a JSON summary to perf/results.json and prints a table.
"""
import http.client
import json
import os
import platform
import random
import statistics
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

BASE = urllib.parse.urlparse(os.environ.get("BASE_URL", "http://localhost:8080"))
RUN = f"{random.randrange(10**8):08d}"
local = threading.local()


def conn():
    c = getattr(local, "c", None)
    if c is None:
        c = http.client.HTTPConnection(BASE.hostname, BASE.port or 80, timeout=60)
        local.c = c
    return c


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    t0 = time.perf_counter()
    for attempt in range(2):
        try:
            c = conn()
            c.request(method, "/api/v1" + path, body=data, headers=headers)
            r = c.getresponse()
            raw = r.read()
            ms = (time.perf_counter() - t0) * 1000
            return r.status, (json.loads(raw) if raw else None), ms
        except (http.client.HTTPException, ConnectionError):
            local.c = None
            if attempt:
                raise


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def summarise(name, results, wall):
    lat = [r[1] for r in results]
    codes = {}
    for code, _ in results:
        codes[str(code)] = codes.get(str(code), 0) + 1
    return {
        "scenario": name, "requests": len(results), "seconds": round(wall, 2),
        "req_per_sec": round(len(results) / wall, 1),
        "p50_ms": round(pct(lat, 50), 1), "p95_ms": round(pct(lat, 95), 1),
        "p99_ms": round(pct(lat, 99), 1), "max_ms": round(max(lat), 1),
        "mean_ms": round(statistics.mean(lat), 1), "status_codes": codes,
    }


def run_parallel(fn, items, workers):
    out = []
    lock = threading.Lock()

    def wrap(x):
        code, ms = fn(x)
        with lock:
            out.append((code, ms))

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        list(ex.map(wrap, items))
    return out, time.perf_counter() - t0


def setup(n_providers=10, slots_per_provider=100, n_customers=1000):
    cats = ["LEGAL", "TAX", "FINANCIAL_ADVISORY", "AUDIT"]
    start_day = date.today() + timedelta(days=50)
    slot_ids = []
    for p in range(n_providers):
        _, prov, _ = call("POST", "/providers", {"name": f"Load {RUN} P{p}", "serviceCategory": cats[p % len(cats)]})
        batch = []
        for i in range(slots_per_provider):
            day = start_day + timedelta(days=i // 10)
            hour = 8 + i % 10
            batch.append({"date": day.isoformat(), "startTime": f"{hour:02d}:00", "endTime": f"{hour + 1:02d}:00"})
        code, created, _ = call("POST", f"/providers/{prov['id']}/slots/batch", {"slots": batch})
        assert code == 201, (code, created)
        slot_ids += [s["id"] for s in created]
    customers = []
    for i in range(n_customers):
        _, c, _ = call("POST", "/customers", {"name": f"Load {i}", "email": f"load{i}.{RUN}@example.com"})
        customers.append(c["id"])
    return slot_ids, customers, start_day


def main():
    print(f"Target {BASE.geturl()}  run {RUN}")
    t0 = time.perf_counter()
    slots, customers, start_day = setup()
    print(f"Setup: {len(slots)} slots, {len(customers)} customers in {time.perf_counter() - t0:.1f}s")
    results = []

    # 1. Bookings: distinct customer -> distinct slot
    pairs = list(zip(customers, slots))
    random.shuffle(pairs)

    def book(pair):
        code, _, ms = call("POST", "/appointments", {"customerId": pair[0], "slotId": pair[1]})
        return code, ms

    res, wall = run_parallel(book, pairs, 50)
    results.append(summarise("Book 1,000 different slots (50 parallel clients)", res, wall))

    # 2. Search with mixed filters
    cats = ["LEGAL", "TAX", "FINANCIAL_ADVISORY", "AUDIT"]

    def search(i):
        q = {"page": i % 3, "size": 20, "sort": "startTime,asc"}
        if i % 2 == 0:
            q["serviceCategory"] = cats[i % 4]
        if i % 3 == 0:
            d = start_day + timedelta(days=i % 10)
            q["fromDate"] = d.isoformat()
            q["toDate"] = (d + timedelta(days=2)).isoformat()
        code, _, ms = call("GET", "/slots/available?" + urllib.parse.urlencode(q))
        return code, ms

    res, wall = run_parallel(search, range(2000), 50)
    results.append(summarise("Search 2,000 times, mixed filters (50 parallel clients)", res, wall))

    # 3. Hot slot: 200 customers, one slot, released together
    _, prov, _ = call("POST", "/providers", {"name": f"Load {RUN} hot", "serviceCategory": "LEGAL"})
    _, hot, _ = call("POST", f"/providers/{prov['id']}/slots",
                     {"date": (start_day + timedelta(days=30)).isoformat(), "startTime": "10:00", "endTime": "11:00"})
    racers = []
    for i in range(200):
        _, c, _ = call("POST", "/customers", {"name": f"Hot {i}", "email": f"hot{i}.{RUN}@example.com"})
        racers.append(c["id"])
    gate = threading.Barrier(200)

    def race(cid):
        conn()  # open the connection before the gate so all requests leave together
        gate.wait()
        code, _, ms = call("POST", "/appointments", {"customerId": cid, "slotId": hot["id"]})
        return code, ms

    res, wall = run_parallel(race, racers, 200)
    results.append(summarise("200 customers, one slot, same instant", res, wall))

    env = {"python": platform.python_version(), "machine": platform.machine(), "cpus": os.cpu_count(),
           "note": "app, PostgreSQL and load generator on the same cloud container"}
    os.makedirs("perf", exist_ok=True)
    with open("perf/results.json", "w") as f:
        json.dump({"run": RUN, "environment": env, "results": results}, f, indent=2)

    print(f"\n{'Scenario':58} {'req/s':>7} {'p50':>7} {'p95':>7} {'p99':>7}  status codes")
    for r in results:
        print(f"{r['scenario']:58} {r['req_per_sec']:>7} {r['p50_ms']:>6}ms {r['p95_ms']:>6}ms {r['p99_ms']:>6}ms  {r['status_codes']}")
    print(f"\nEnvironment: {env}")


if __name__ == "__main__":
    main()
