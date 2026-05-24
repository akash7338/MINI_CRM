package com.minigenesys.telephony.config;

import com.minigenesys.telephony.model.PhoneNumberMapping;
import com.minigenesys.telephony.repository.PhoneNumberMappingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder {

    private final PhoneNumberMappingRepository repository;

    @PostConstruct
    public void seed() {
        log.info("Seeding phone number mappings...");
        
        // 1. Twilio Number
        if (!repository.existsById("+19783505660")) {
            repository.save(PhoneNumberMapping.builder()
                    .phoneNumber("+19783505660")
                    .tenantId("tenant-twilio")
                    .telephonyProvider("TWILIO")
                    .build());
            log.info("Seeded Twilio mapping for +19783505660");
        }

        // 2. Telnyx Number
        if (!repository.existsById("+12014269044")) {
            repository.save(PhoneNumberMapping.builder()
                    .phoneNumber("+12014269044")
                    .tenantId("tenant-freeswitch")
                    .telephonyProvider("FREESWITCH")
                    .build());
            log.info("Seeded Telnyx mapping for +12014269044");
        }
    }
}
