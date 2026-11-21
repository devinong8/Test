package com.hackathon.appointments.config;

import com.hackathon.appointments.repository.ProviderRepository;
import com.hackathon.appointments.service.DirectoryService;
import com.hackathon.appointments.service.SlotService;
import com.hackathon.appointments.web.dto.CreateCustomerRequest;
import com.hackathon.appointments.web.dto.CreateProviderRequest;
import com.hackathon.appointments.web.dto.CreateSlotRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Seeds an empty database with demo data so the API can be explored immediately in Swagger UI. */
@Component
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ProviderRepository providers;
    private final DirectoryService directory;
    private final SlotService slots;
    private final Clock clock;

    public DemoDataSeeder(ProviderRepository providers, DirectoryService directory, SlotService slots, Clock clock) {
        this.providers = providers;
        this.directory = directory;
        this.slots = slots;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (providers.count() > 0) {
            return;
        }
        var legal = directory.createProvider(new CreateProviderRequest("Tan & Partners LLP", "LEGAL"));
        var tax = directory.createProvider(new CreateProviderRequest("Lim Tax Advisory", "TAX"));
        var fa = directory.createProvider(new CreateProviderRequest("Harbour Wealth Advisors", "FINANCIAL_ADVISORY"));
        directory.createCustomer(new CreateCustomerRequest("Alice Lim", "alice@example.com"));
        directory.createCustomer(new CreateCustomerRequest("Bob Tan", "bob@example.com"));

        // Next 5 business days, 09:00-12:00 in one-hour slots, for each provider.
        LocalDate day = LocalDate.now(clock).plusDays(1);
        int added = 0;
        while (added < 5) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                List<CreateSlotRequest> batch = new ArrayList<>();
                for (int h = 9; h < 12; h++) {
                    batch.add(new CreateSlotRequest(day, LocalTime.of(h, 0), LocalTime.of(h + 1, 0)));
                }
                slots.createSlots(legal.getId(), batch);
                slots.createSlots(tax.getId(), batch);
                slots.createSlots(fa.getId(), batch);
                added++;
            }
            day = day.plusDays(1);
        }
        log.info("Seeded demo data: 3 providers (ids 1-3), 2 customers (ids 1-2), 45 slots");
    }
}
