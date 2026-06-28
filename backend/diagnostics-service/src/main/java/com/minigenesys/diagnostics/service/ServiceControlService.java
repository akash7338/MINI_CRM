package com.minigenesys.diagnostics.service;

import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties;
import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties.ServiceEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceControlService {

    private final DiagnosticsProperties properties;

    public boolean isControllable(String serviceName) {
        return properties.getServices() != null && properties.getServices().containsKey(serviceName);
    }

    public Map<String, Object> stop(String serviceName) {
        Map<String, Object> result = baseResult(serviceName, "stop");

        Integer port = resolvePort(serviceName);
        if (port == null) {
            result.put("success", false);
            result.put("message", "Unknown service port");
            return result;
        }

        try {
            String pids = listeningPids(port);
            if (pids.isEmpty()) {
                result.put("success", true);
                result.put("message", "Service was not running");
                return result;
            }
            for (String pid : pids.split("\\s+")) {
                runCommand("kill " + pid);
            }
            Thread.sleep(1500);
            String stillUp = listeningPids(port);
            if (!stillUp.isEmpty()) {
                for (String pid : stillUp.split("\\s+")) {
                    runCommand("kill -9 " + pid);
                }
            }
            result.put("success", true);
            result.put("message", "Stopped service on port " + port);
        } catch (Exception e) {
            log.error("Failed to stop {}", serviceName, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> start(String serviceName) {
        Map<String, Object> result = baseResult(serviceName, "start");

        Path jar = resolveJar(serviceName);
        if (jar == null || !Files.exists(jar)) {
            result.put("success", false);
            result.put("message", "Jar not found: " + (jar == null ? "unknown" : jar.toString())
                    + " (build it with ./gradlew :" + serviceName + ":bootJar)");
            return result;
        }

        Integer port = resolvePort(serviceName);
        try {
            if (port != null && !listeningPids(port).isEmpty()) {
                result.put("success", true);
                result.put("message", "Service already running on port " + port);
                return result;
            }
            String consoleFile = "logs/" + serviceName + ".console.log";
            String pidFile = "logs/" + serviceName + ".pid";
            String cmd = "source \"$HOME/.envs/minigenesys.env\" 2>/dev/null; "
                    + "nohup java -jar \"" + jar + "\" > \"" + consoleFile + "\" 2>&1 & "
                    + "echo $! > \"" + pidFile + "\"";

            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();

            result.put("success", true);
            result.put("message", "Start command issued for " + serviceName);
        } catch (Exception e) {
            log.error("Failed to start {}", serviceName, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> restart(String serviceName) {
        Map<String, Object> result = baseResult(serviceName, "restart");
        Map<String, Object> stop = stop(serviceName);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> start = start(serviceName);
        result.put("success", Boolean.TRUE.equals(start.get("success")));
        result.put("stop", stop);
        result.put("start", start);
        return result;
    }

    private Map<String, Object> baseResult(String serviceName, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", serviceName);
        result.put("action", action);
        return result;
    }

    private Integer resolvePort(String serviceName) {
        ServiceEntry entry = properties.getServices() == null ? null : properties.getServices().get(serviceName);
        if (entry == null || entry.getUrl() == null) {
            return null;
        }
        try {
            String url = entry.getUrl();
            int idx = url.lastIndexOf(':');
            if (idx < 0) {
                return null;
            }
            String portStr = url.substring(idx + 1).replaceAll("[^0-9]", "");
            return portStr.isEmpty() ? null : Integer.parseInt(portStr);
        } catch (Exception e) {
            return null;
        }
    }

    private Path resolveJar(String serviceName) {
        return Paths.get(serviceName, "build", "libs", serviceName + "-0.0.1-SNAPSHOT.jar");
    }

    private String listeningPids(int port) throws Exception {
        String out = runCommand("lsof -nP -iTCP:" + port + " -sTCP:LISTEN -t");
        return out == null ? "" : out.trim();
    }

    private String runCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return sb.toString();
    }
}
