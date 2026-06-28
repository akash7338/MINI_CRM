package com.minigenesys.freeswitch.controller;

import com.minigenesys.freeswitch.service.FreeswitchEslService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/v1/freeswitch/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final FreeswitchEslService eslService;

    @GetMapping("/sofia-status")
    public ResponseEntity<Map<String, Object>> sofiaStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!eslService.isConnected()) {
            result.put("error", "ESL not connected");
            return ResponseEntity.ok(result);
        }
        try {
            String raw = eslService.executeSyncApiCommand("sofia", "status");
            result.put("raw", raw);
            result.put("profiles", parseSofiaStatusProfiles(raw));
        } catch (Exception e) {
            log.error("Failed to get sofia status", e);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/gateway-status")
    public ResponseEntity<Map<String, Object>> gatewayStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!eslService.isConnected()) {
            result.put("error", "ESL not connected");
            return ResponseEntity.ok(result);
        }
        try {
            String raw = eslService.executeSyncApiCommand("sofia", "status gateway telnyx");
            result.put("raw", raw);
            result.put("parsed", parseKeyValueOutput(raw));
        } catch (Exception e) {
            log.error("Failed to get gateway status", e);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/profile/{profileName}")
    public ResponseEntity<Map<String, Object>> profileStatus(@PathVariable String profileName) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!eslService.isConnected()) {
            result.put("error", "ESL not connected");
            return ResponseEntity.ok(result);
        }
        try {
            String raw = eslService.executeSyncApiCommand("sofia", "status profile " + profileName);
            result.put("raw", raw);
            result.put("parsed", parseKeyValueOutput(raw));
        } catch (Exception e) {
            log.error("Failed to get profile status for {}", profileName, e);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/active-channels")
    public ResponseEntity<Map<String, Object>> activeChannels() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!eslService.isConnected()) {
            result.put("error", "ESL not connected");
            return ResponseEntity.ok(result);
        }
        try {
            String raw = eslService.executeSyncApiCommand("show", "channels");
            result.put("raw", raw);
            result.put("channels", parseShowChannels(raw));
        } catch (Exception e) {
            log.error("Failed to get active channels", e);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private List<Map<String, String>> parseSofiaStatusProfiles(String raw) {
        List<Map<String, String>> profiles = new ArrayList<>();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("=") || line.startsWith("Name")
                    || line.startsWith("-")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length >= 4) {
                Map<String, String> profile = new LinkedHashMap<>();
                profile.put("name", parts[0]);
                profile.put("type", parts[1]);
                profile.put("data", parts[2]);
                profile.put("state", parts[3]);
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private Map<String, String> parseKeyValueOutput(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("=")) continue;
            Matcher m = Pattern.compile("^(.+?)\\s{2,}(.+)$").matcher(line);
            if (m.find()) {
                parsed.put(m.group(1).trim(), m.group(2).trim());
            }
        }
        return parsed;
    }

    private List<Map<String, String>> parseShowChannels(String raw) {
        List<Map<String, String>> channels = new ArrayList<>();
        String[] lines = raw.split("\n");
        if (lines.length < 2) return channels;

        String[] headers = lines[0].split(",");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.contains(" total.")) break;
            String[] values = line.split(",", -1);
            Map<String, String> channel = new LinkedHashMap<>();
            for (int j = 0; j < Math.min(headers.length, values.length); j++) {
                channel.put(headers[j].trim(), values[j].trim());
            }
            channels.add(channel);
        }
        return channels;
    }
}
