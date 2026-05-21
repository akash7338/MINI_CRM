package com.minigenesys.freeswitch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FreeSWITCH integration service.
 * Phase 3: ESL connection and event logging only.
 * @EnableKafka is not needed until Phase 5 (Kafka consumer for routing events).
 */
@SpringBootApplication
public class FreeswitchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FreeswitchServiceApplication.class, args);
    }
}
