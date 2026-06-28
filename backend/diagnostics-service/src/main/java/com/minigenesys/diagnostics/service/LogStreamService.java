package com.minigenesys.diagnostics.service;

import com.minigenesys.diagnostics.config.DiagnosticsConfig.DiagnosticsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogStreamService {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int INITIAL_BACKLOG_LINES = 50;
    private static final long POLL_INTERVAL_MS = 300L;
    private static final int READ_BUFFER = 16 * 1024;

    private final DiagnosticsProperties properties;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "log-stream");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter stream(String service, String level) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Path logFile = Paths.get(properties.getLogPath(), service + ".log");

        if (!Files.exists(logFile)) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("Log file not found: " + service + ".log"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        AtomicBoolean active = new AtomicBoolean(true);
        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(() -> {
            active.set(false);
            emitter.complete();
        });
        emitter.onError(e -> active.set(false));

        String levelFilter = (level == null || level.isBlank()) ? null : level.toUpperCase();

        executor.submit(() -> tail(emitter, logFile, levelFilter, active));
        return emitter;
    }

    private void tail(SseEmitter emitter, Path logFile, String levelFilter, AtomicBoolean active) {
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long position = backlogStart(raf);
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[READ_BUFFER];

            while (active.get()) {
                long length = raf.length();
                if (length < position) {
                    // File was truncated/rotated (e.g. service restarted); start over.
                    position = 0;
                    lineBuf.reset();
                }
                if (length > position) {
                    raf.seek(position);
                    int read;
                    while ((read = raf.read(buf)) != -1) {
                        for (int i = 0; i < read; i++) {
                            byte b = buf[i];
                            if (b == '\n') {
                                emitLine(emitter, lineBuf.toString(StandardCharsets.UTF_8), levelFilter);
                                lineBuf.reset();
                            } else if (b != '\r') {
                                lineBuf.write(b);
                            }
                        }
                    }
                    position = raf.getFilePointer();
                } else {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            }
        } catch (IOException e) {
            active.set(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Log stream ended for {}: {}", logFile, e.getMessage());
            active.set(false);
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter may already be closed
            }
        }
    }

    private void emitLine(SseEmitter emitter, String line, String levelFilter) throws IOException {
        if (levelFilter == null || line.contains(levelFilter)) {
            emitter.send(SseEmitter.event().data(line));
        }
    }

    private long backlogStart(RandomAccessFile raf) throws IOException {
        long length = raf.length();
        long pos = length - 1;
        int newlines = 0;
        while (pos > 0) {
            raf.seek(pos);
            if (raf.read() == '\n') {
                newlines++;
                if (newlines > INITIAL_BACKLOG_LINES) {
                    return pos + 1;
                }
            }
            pos--;
        }
        return 0;
    }
}
