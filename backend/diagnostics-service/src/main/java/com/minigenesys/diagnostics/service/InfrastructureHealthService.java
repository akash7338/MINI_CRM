package com.minigenesys.diagnostics.service;

import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties;
import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties.HostPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfrastructureHealthService {

    private final DiagnosticsProperties properties;

    public Map<String, Object> checkAll() {
        Map<String, Object> infra = new LinkedHashMap<>();
        DiagnosticsProperties.InfrastructureConfig cfg = properties.getInfrastructure();

        if (cfg != null) {
            if (cfg.getPostgres() != null) {
                infra.put("postgres", checkTcp(cfg.getPostgres(), "Postgres"));
            }
            if (cfg.getRedis() != null) {
                infra.put("redis", checkTcp(cfg.getRedis(), "Redis"));
            }
            if (cfg.getKafka() != null) {
                infra.put("kafka", checkTcp(cfg.getKafka(), "Kafka"));
            }
            if (cfg.getFreeswitchEsl() != null) {
                infra.put("freeswitch-esl", checkTcp(cfg.getFreeswitchEsl(), "FreeSWITCH ESL"));
            }
        }

        return infra;
    }

    private Map<String, String> checkTcp(HostPort hp, String label) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hp.getHost(), hp.getPort()), 2000);
            return Map.of("status", "UP", "endpoint", hp.getHost() + ":" + hp.getPort());
        } catch (Exception e) {
            log.warn("{} health check failed at {}:{}: {}", label, hp.getHost(), hp.getPort(), e.getMessage());
            return Map.of("status", "DOWN", "endpoint", hp.getHost() + ":" + hp.getPort(), "error", e.getMessage());
        }
    }
}
