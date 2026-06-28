package com.minigenesys.diagnostics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Configuration
public class DiagnosticsConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    @Bean
    @ConfigurationProperties(prefix = "diagnostics")
    public DiagnosticsProperties diagnosticsProperties() {
        return new DiagnosticsProperties();
    }

    @Data
    public static class DiagnosticsProperties {
        private String logPath = "logs";
        private Map<String, ServiceEntry> services;
        private InfrastructureConfig infrastructure;

        @Data
        public static class ServiceEntry {
            private String url;
            private String healthPath;
        }

        @Data
        public static class InfrastructureConfig {
            private HostPort postgres;
            private HostPort redis;
            private HostPort kafka;
            private HostPort freeswitchEsl;
        }

        @Data
        public static class HostPort {
            private String host = "localhost";
            private int port;
        }
    }
}
