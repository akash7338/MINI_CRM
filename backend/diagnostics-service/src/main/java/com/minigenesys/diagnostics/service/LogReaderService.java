package com.minigenesys.diagnostics.service;

import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogReaderService {

    private final DiagnosticsProperties properties;

    public List<Map<String, Object>> listLogFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        Path logDir = Paths.get(properties.getLogPath());

        if (!Files.isDirectory(logDir)) {
            log.warn("Log directory does not exist: {}", logDir.toAbsolutePath());
            return files;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.log")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.endsWith(".console.log")) {
                    continue;
                }
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", entry.getFileName().toString());
                info.put("service", entry.getFileName().toString().replace(".log", ""));
                info.put("sizeBytes", Files.size(entry));
                info.put("lastModified", Files.getLastModifiedTime(entry).toString());
                files.add(info);
            }
        } catch (IOException e) {
            log.error("Failed to list log files", e);
        }

        return files;
    }

    public Map<String, Object> readLogs(String service, String level, int lines) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path logFile = Paths.get(properties.getLogPath(), service + ".log");

        if (!Files.exists(logFile)) {
            result.put("error", "Log file not found: " + service + ".log");
            return result;
        }

        try {
            List<String> tailLines = tailFile(logFile, lines * 5);

            List<String> filtered;
            if (level != null && !level.isEmpty()) {
                String upperLevel = level.toUpperCase();
                filtered = tailLines.stream()
                        .filter(line -> line.contains(upperLevel))
                        .collect(Collectors.toList());
            } else {
                filtered = tailLines.stream()
                        .filter(line -> line.contains("ERROR") || line.contains("WARN"))
                        .collect(Collectors.toList());
            }

            int limit = Math.min(lines, filtered.size());
            List<String> output = filtered.subList(Math.max(0, filtered.size() - limit), filtered.size());

            result.put("service", service);
            result.put("file", logFile.getFileName().toString());
            result.put("totalLines", output.size());
            result.put("level", level != null ? level.toUpperCase() : "ERROR,WARN");
            result.put("entries", output);
        } catch (IOException e) {
            log.error("Failed to read log file {}", logFile, e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    private List<String> tailFile(Path file, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return lines;

            long pos = fileLength - 1;
            int count = 0;
            StringBuilder sb = new StringBuilder();

            while (pos >= 0 && count < maxLines) {
                raf.seek(pos);
                int ch = raf.read();
                if (ch == '\n') {
                    if (sb.length() > 0) {
                        lines.add(sb.reverse().toString());
                        sb = new StringBuilder();
                        count++;
                    }
                } else {
                    sb.append((char) ch);
                }
                pos--;
            }
            if (sb.length() > 0 && count < maxLines) {
                lines.add(sb.reverse().toString());
            }

            Collections.reverse(lines);
        }
        return lines;
    }
}
