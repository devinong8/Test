package com.hackathon.appointments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.appointments.exception.ConflictException;
import com.hackathon.appointments.service.AppointmentService;
import com.hackathon.appointments.service.SlotService;
import com.hackathon.appointments.web.dto.BookAppointmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.hackathon.appointments.web.dto.CreateSlotRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.File;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = TestProfiles.class)
class AppointmentApiIntegrationTest {

    static final ZoneId SGT = ZoneId.of("Asia/Singapore");
    static final Instant START = LocalDateTime.of(2026, 11, 21, 10, 0).atZone(SGT).toInstant();

    static {
        // fresh database for every test run
        File dir = new File("target/test-db");
        dir.mkdirs();
        for (File f : dir.listFiles()) {
            f.delete();
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START, SGT);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired AppointmentService appointmentService;
    @Autowired SlotService slotService;
    @Autowired JdbcTemplate jdbc;

    final LocalDate tomorrow = LocalDate.of(2026, 11, 22);

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    /* ------------------------------------------------------------------ helpers */

    ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    long newProvider(String category) throws Exception {
        return body(postJson("/api/v1/providers", Map.of("name", "P-" + UUID.randomUUID(), "serviceCategory", category))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    long newCustomer() throws Exception {
        return body(postJson("/api/v1/customers", Map.of("name", "C", "email", UUID.randomUUID() + "@ex.com"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    ResultActions createSlot(long providerId, LocalDate date, String start, String end) throws Exception {
        return postJson("/api/v1/providers/" + providerId + "/slots",
                Map.of("date", date.toString(), "startTime", start, "endTime", end));
    }

    long slot(long providerId, LocalDate date, String start, String end) throws Exception {
        return body(createSlot(providerId, date, start, end).andExpect(status().isCreated())).get("id").asLong();
    }

    ResultActions book(long customerId, long slotId) throws Exception {
        return postJson("/api/v1/appointments", Map.of("customerId", customerId, "slotId", slotId));
    }

    long booked(long customerId, long slotId) throws Exception {
        return body(book(customerId, slotId).andExpect(status().isCreated())).get("id").asLong();
    }

    ResultActions cancel(long appointmentId, long customerId, String reason) throws Exception {
        return postJson("/api/v1/appointments/" + appointmentId + "/cancel", Map.of("customerId", customerId, "reason", reason));
    }

    /** Fires all tasks at the same instant; returns {successes, conflicts}. Any other exception fails the test. */
    static int[] race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Object> t : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    t.call();
                    ok.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new int[]{ok.get(), conflict.get()};
    }

    /* ------------------------------------------------------------------ 1. slot definition */

    @Nested
    class DefineSlots {

        @Test
        void createdSlotIsAvailable() throws Exception {
            long p = newProvider("LEGAL");
            createSlot(p, tomorrow, "09:00", "10:00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.providerId").value(p))
                    .andExpect(jsonPath("$.startTime").value("09:00"));
        }

        @Test
        void overlappingSlotRejectedButBackToBackAllowed() throws Exception {
            long p = newProvider("LEGAL");
            slot(p, tomorrow, "09:00", "10:00");
            createSlot(p, tomorrow, "09:30", "10:30").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
            createSlot(p, tomorrow, "08:00", "12:00").andExpect(status().isConflict()); // encloses
            createSlot(p, tomorrow, "09:15", "09:45").andExpect(status().isConflict()); // inside
            createSlot(p, tomorrow, "10:00", "11:00").andExpect(status().isCreated());  // touches only
            // a different provider is unaffected
            createSlot(newProvider("LEGAL"), tomorrow, "09:00", "10:00").andExpect(status().isCreated());
        }

        @Test
        void invalidTimeRangesAreBadRequests() throws Exception {
            long p = newProvider("LEGAL");
            createSlot(p, tomorrow, "10:00", "09:00").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
            createSlot(p, tomorrow, "10:00", "10:00").andExpect(status().isBadRequest());
            createSlot(p, LocalDate.of(2026, 11, 20), "10:00", "11:00").andExpect(status().isBadRequest()); // past
            createSlot(p, tomorrow, "10:00", "10:05").andExpect(status().isBadRequest()); // < 15 min
            postJson("/api/v1/providers/" + p + "/slots", Map.of("date", tomorrow.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("startTime", "endTime")));
            postJson("/api/v1/providers/" + p + "/slots", Map.of("date", tomorrow.toString(), "startTime", "9am", "endTime", "10:00"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void providerCanRescheduleAndDeleteUnbookedSlots() throws Exception {
            long p = newProvider("LEGAL");
            long s = slot(p, tomorrow, "09:00", "10:00");
            slot(p, tomorrow, "11:00", "12:00");
            String url = "/api/v1/providers/" + p + "/slots/" + s;
            mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"date\":\"" + tomorrow + "\",\"startTime\":\"09:30\",\"endTime\":\"10:30\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.startTime").value("09:30"));
            // moving onto its own old time is fine (excludes itself); onto another slot is not
            mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"date\":\"" + tomorrow + "\",\"startTime\":\"10:30\",\"endTime\":\"11:30\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));
            mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"date\":\"" + tomorrow + "\",\"startTime\":\"10:30\",\"endTime\":\"10:00\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete(url)).andExpect(status().isNoContent());
            mvc.perform(get(url)).andExpect(status().isNotFound());
            mvc.perform(delete(url)).andExpect(status().isNotFound());
        }

        @Test
        void bookedOrPreviouslyBookedSlotsCannotBeChangedOrDeleted() throws Exception {
            long p = newProvider("LEGAL");
            long c = newCustomer();
            long s = slot(p, tomorrow, "09:00", "10:00");
            long a = booked(c, s);
            String url = "/api/v1/providers/" + p + "/slots/" + s;
            mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"date\":\"" + tomorrow + "\",\"startTime\":\"13:00\",\"endTime\":\"14:00\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_ALREADY_BOOKED"));
            mvc.perform(delete(url)).andExpect(status().isConflict());
            mvc.perform(post(url + "/lock")).andExpect(status().isConflict());
            cancel(a, c, "changed mind").andExpect(status().isOk());
            mvc.perform(delete(url)).andExpect(status().isConflict()) // history must be kept
                    .andExpect(jsonPath("$.message", containsString("lock it instead")));
        }

        @Test
        void concurrentOverlappingSlotCreationProducesExactlyOneSlot() throws Exception {
            long p = newProvider("LEGAL");
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String start = "09:%02d".formatted(i * 5); // 09:00, 09:05 ... all overlap each other
                tasks.add(() -> slotService.createSlot(p, new CreateSlotRequest(tomorrow,
                        LocalTime.parse(start), LocalTime.parse(start).plusHours(1))));
            }
            int[] result = race(tasks);
            assertThat(result[0]).isEqualTo(1);
            assertThat(result[1]).isEqualTo(9);
            mvc.perform(get("/api/v1/providers/" + p + "/slots")).andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void unknownProviderIs404() throws Exception {
            createSlot(99999, tomorrow, "09:00", "10:00").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
        }

        @Test
        void batchIsAllOrNothing() throws Exception {
            long p = newProvider("TAX");
            postJson("/api/v1/providers/" + p + "/slots/batch", Map.of("slots", List.of(
                    Map.of("date", tomorrow.toString(), "startTime", "09:00", "endTime", "10:00"),
                    Map.of("date", tomorrow.toString(), "startTime", "09:30", "endTime", "10:30"))))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/v1/providers/" + p + "/slots")).andExpect(jsonPath("$.totalElements").value(0));

            postJson("/api/v1/providers/" + p + "/slots/batch", Map.of("slots", List.of(
                    Map.of("date", tomorrow.toString(), "startTime", "09:00", "endTime", "10:00"),
                    Map.of("date", tomorrow.toString(), "startTime", "10:00", "endTime", "11:00"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    /* ------------------------------------------------------------------ 2. search */

    @Nested
    class SearchSlots {

        @Test
        void excludesBookedLockedAndExpiredAndFiltersByCategoryProviderAndDate() throws Exception {
            String cat = "CAT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            long p1 = newProvider(cat);
            long p2 = newProvider(cat);
            long c = newCustomer();
            LocalDate today = LocalDate.of(2026, 11, 21);
            long expiredSoon = slot(p1, today, "10:30", "11:00");   // will have started
            long bookedSlot = slot(p1, tomorrow, "09:00", "10:00");
            long lockedSlot = slot(p1, tomorrow, "10:00", "11:00");
            long free1 = slot(p1, tomorrow, "11:00", "12:00");
            long free2 = slot(p2, tomorrow.plusDays(2), "09:00", "10:00");
            booked(c, bookedSlot);
            mvc.perform(post("/api/v1/providers/" + p1 + "/slots/" + lockedSlot + "/lock")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOCKED"));
            clock.advance(Duration.ofMinutes(45)); // now 10:45, expiredSoon has started

            String base = "/api/v1/slots/available?serviceCategory=" + cat.toLowerCase();
            mvc.perform(get(base)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[*].id", contains((int) free1, (int) free2)))
                    .andExpect(jsonPath("$.content[*].id", not(hasItems((int) expiredSoon, (int) bookedSlot, (int) lockedSlot))));

            mvc.perform(get(base + "&providerId=" + p2)).andExpect(jsonPath("$.content[*].id", contains((int) free2)));
            mvc.perform(get(base + "&fromDate=" + tomorrow + "&toDate=" + tomorrow))
                    .andExpect(jsonPath("$.content[*].id", contains((int) free1)));
        }

        @Test
        void paginatesAndSorts() throws Exception {
            String cat = "PG_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            long p = newProvider(cat);
            List<Long> ids = new ArrayList<>();
            for (int h = 9; h < 14; h++) {
                ids.add(slot(p, tomorrow, "%02d:00".formatted(h), "%02d:00".formatted(h + 1)));
            }
            String base = "/api/v1/slots/available?serviceCategory=" + cat;
            mvc.perform(get(base + "&size=2&page=0&sort=startTime,desc"))
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.content[*].startTime", contains("13:00", "12:00")));
            mvc.perform(get(base + "&size=2&page=2"))
                    .andExpect(jsonPath("$.content[*].startTime", contains("13:00")))
                    .andExpect(jsonPath("$.last").value(true));
        }

        @Test
        void rejectsBadSearchParams() throws Exception {
            mvc.perform(get("/api/v1/slots/available?sort=password")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/slots/available?size=0")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/slots/available?size=1000")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/slots/available?fromDate=2026-12-10&toDate=2026-12-01")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/slots/available?fromDate=not-a-date")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    /* ------------------------------------------------------------------ 3. booking */

    @Nested
    class Booking {

        @Test
        void bookBySlotIdAndByTimeRange() throws Exception {
            long p = newProvider("LEGAL");
            long c = newCustomer();
            long s1 = slot(p, tomorrow, "09:00", "10:00");
            slot(p, tomorrow, "10:00", "11:00");
            book(c, s1).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("BOOKED"))
                    .andExpect(jsonPath("$.slot.status").value("BOOKED"))
                    .andExpect(jsonPath("$.customerId").value(c));
            postJson("/api/v1/appointments", Map.of("customerId", c, "providerId", p, "date", tomorrow.toString(),
                    "startTime", "10:00", "endTime", "11:00"))
                    .andExpect(status().isCreated());
            postJson("/api/v1/appointments", Map.of("customerId", c, "providerId", p, "date", tomorrow.toString(),
                    "startTime", "15:00", "endTime", "16:00"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SLOT_NOT_FOUND"));
        }

        @Test
        void doubleBookingIs409() throws Exception {
            long p = newProvider("LEGAL");
            long s = slot(p, tomorrow, "09:00", "10:00");
            booked(newCustomer(), s);
            book(newCustomer(), s).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_ALREADY_BOOKED"));
        }

        @Test
        void lockedOrStartedSlotsCannotBeBooked() throws Exception {
            long p = newProvider("LEGAL");
            long c = newCustomer();
            long locked = slot(p, tomorrow, "09:00", "10:00");
            mvc.perform(post("/api/v1/providers/" + p + "/slots/" + locked + "/lock")).andExpect(status().isOk());
            book(c, locked).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_NOT_AVAILABLE"));

            long soon = slot(p, LocalDate.of(2026, 11, 21), "11:00", "12:00");
            clock.advance(Duration.ofHours(1).plusMinutes(1));
            book(c, soon).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_EXPIRED"));
        }

        @Test
        void bookingWithInvalidTimeRangeIs400() throws Exception {
            long p = newProvider("LEGAL");
            postJson("/api/v1/appointments", Map.of("customerId", newCustomer(), "providerId", p, "date", tomorrow.toString(),
                    "startTime", "11:00", "endTime", "10:00"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        }

        @Test
        void sameCustomerRacingForOverlappingSlotsGetsExactlyOne() throws Exception {
            long c = newCustomer();
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                long s = slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00"); // 10 providers, same hour
                tasks.add(() -> appointmentService.book(new BookAppointmentRequest(c, s, null, null, null, null, null)));
            }
            int[] result = race(tasks);
            assertThat(result[0]).isEqualTo(1);
            assertThat(result[1]).isEqualTo(9);
            mvc.perform(get("/api/v1/customers/" + c + "/appointments")).andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void customerCannotHoldOverlappingBookings() throws Exception {
            long c = newCustomer();
            long a = slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00");
            long b = slot(newProvider("TAX"), tomorrow, "09:30", "10:30");
            booked(c, a);
            book(c, b).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUSTOMER_DOUBLE_BOOKING"));
        }

        @Test
        void notFoundAndValidationErrors() throws Exception {
            long s = slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00");
            book(99999, s).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
            book(newCustomer(), 99999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SLOT_NOT_FOUND"));
            postJson("/api/v1/appointments", Map.of("slotId", s)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("customerId")));
            postJson("/api/v1/appointments", Map.of("customerId", 1, "slotId", s, "providerId", 1))
                    .andExpect(status().isBadRequest()); // both references
            postJson("/api/v1/appointments", Map.of("customerId", 1, "slotId", s, "colour", "red"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("colour")));
            mvc.perform(post("/api/v1/appointments").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/appointments/abc")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/appointments/424242")).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound());
        }

        @Test
        void concurrentBookingsOfOneSlotProduceExactlyOneWinner() throws Exception {
            long s = slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00");
            int n = 20;
            List<Long> customers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                customers.add(newCustomer());
            }
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (Long c : customers) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        appointmentService.book(new BookAppointmentRequest(c, s, null, null, null, null, null));
                        ok.incrementAndGet();
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertThat(ok.get()).isEqualTo(1);
            assertThat(conflict.get()).isEqualTo(n - 1);
        }
    }

    /* ------------------------------------------------------------------ 4. cancellation */

    @Nested
    class Cancellation {

        @Test
        void cancelRecordsReasonAndTimestampAndReleasesSlotForRebooking() throws Exception {
            long p = newProvider("LEGAL");
            long c = newCustomer();
            long s = slot(p, tomorrow, "09:00", "10:00");
            long a = booked(c, s);
            clock.advance(Duration.ofMinutes(5));

            cancel(a, c, "  Schedule conflict ").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancellationReason").value("Schedule conflict"))
                    .andExpect(jsonPath("$.cancelledAt", startsWith("2026-11-21T10:05")))
                    .andExpect(jsonPath("$.slot.status").value("AVAILABLE"));

            mvc.perform(get("/api/v1/providers/" + p + "/slots/" + s)).andExpect(jsonPath("$.status").value("AVAILABLE"));
            long other = newCustomer();
            booked(other, s); // slot can be booked again
            mvc.perform(get("/api/v1/customers/" + c + "/appointments?status=CANCELLED"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void cancellationIsAtomicAndRollsBackIfSlotCannotBeReleased() throws Exception {
            long c = newCustomer();
            long s = slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00");
            long a = booked(c, s);
            // Simulate corruption / an out-of-band change: the slot is no longer BOOKED.
            jdbc.update("update time_slot set status = 'LOCKED' where id = ?", s);

            cancel(a, c, "should roll back").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

            // Nothing was half-written: appointment is still BOOKED with no cancellation data.
            mvc.perform(get("/api/v1/appointments/" + a))
                    .andExpect(jsonPath("$.status").value("BOOKED"))
                    .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                    .andExpect(jsonPath("$.cancellationReason").doesNotExist());
            assertThat(jdbc.queryForObject("select status from time_slot where id = ?", String.class, s)).isEqualTo("LOCKED");
        }

        @Test
        void onlyOwnerCanCancel() throws Exception {
            long c = newCustomer();
            long a = booked(c, slot(newProvider("LEGAL"), tomorrow, "09:00", "10:00"));
            cancel(a, newCustomer(), "not mine").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_APPOINTMENT_OWNER"));
            mvc.perform(get("/api/v1/appointments/" + a)).andExpect(jsonPath("$.status").value("BOOKED"));
        }

        @Test
        void cannotCancelTwiceOrInsideCutoffOrMissing() throws Exception {
            long c = newCustomer();
            long p = newProvider("LEGAL");
            long a = booked(c, slot(p, tomorrow, "09:00", "10:00"));
            cancel(a, c, "first").andExpect(status().isOk());
            cancel(a, c, "second").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("APPOINTMENT_ALREADY_CANCELLED"));

            long late = booked(c, slot(p, LocalDate.of(2026, 11, 21), "10:45", "11:15")); // starts in 45 min (< 60 cut-off)
            cancel(late, c, "too late").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));

            cancel(987654, c, "x").andExpect(status().isNotFound());
            cancel(a, c, "").andExpect(status().isBadRequest());
        }
    }

    /* ------------------------------------------------------------------ 5. expiry */

    @Nested
    class Expiry {
    @Test
    void sweeperMarksStartedSlotsExpired() throws Exception {
        long p = newProvider("LEGAL");
        long s = slot(p, LocalDate.of(2026, 11, 21), "11:00", "12:00");
        clock.advance(Duration.ofHours(2));
        assertThat(slotService.expireStartedSlots()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/providers/" + p + "/slots/" + s)).andExpect(jsonPath("$.status").value("EXPIRED"));
        mvc.perform(post("/api/v1/providers/" + p + "/slots/" + s + "/unlock")).andExpect(status().isConflict());
    }
    }
}
