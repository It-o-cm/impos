package com.intermarche.pos.ui.hardware.scanner;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The REAL barcode-scanner reader of a production till (prod build only):
 * autodiscovers every attached scanner across both input channels, reads each
 * in the mode it presents itself (evdev keyboard or raw hidraw), and pushes
 * every decoded code into the register's scan chain through
 * {@code POST /api/pos/scan} — the same intake the simulator and a keyboard
 * wedge feed, so nothing downstream knows the code came from a physical
 * device. Fleet-oriented: no per-till scanner reconfiguration, the software
 * adapts to whatever mode each unit is in.
 * <p>
 * One daemon thread per device blocks on {@code read(2)} and hands each chunk
 * to the device's {@link ScannerSource} to decode. {@link #stop()} on
 * shutdown releases every device (dropping any grab) and lets the threads
 * end — the maison @PreDestroy pattern, so a redeploy leaves nothing held.
 */
@Singleton
@IfBuildProfile("prod")
public class ScannerReaderService {

    /** The class logger. */
    private static final Logger LOGGER = Logger.getLogger(ScannerReaderService.class);

    /** Whether the reader runs at all (a till with no scanner turns it off). */
    @ConfigProperty(name = "pos.scanner.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * The scanner name signatures, matched case-insensitively against each
     * device's advertised name, on both channels. Default = the common retail
     * brands; override per till for an unusual model — no rebuild.
     */
    @ConfigProperty(name = "pos.scanner.name-patterns",
            defaultValue = "symbol,zebra,honeywell,datalogic,newland,opticon,scanner,barcode")
    List<String> namePatterns;

    /**
     * The loopback endpoint the decoded codes are pushed to — the register's
     * own scan intake. Points at the app itself by default.
     */
    @ConfigProperty(name = "pos.scanner.sink-url",
            defaultValue = "http://127.0.0.1:8080/api/pos/scan")
    String sinkUrl;

    /** The HTTP client pushing codes to the scan intake. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** The reader threads, one per opened device. */
    private final List<Thread> readers = new ArrayList<>();

    /** The opened sources with their file descriptors, released on shutdown. */
    private final List<OpenDevice> openDevices = new ArrayList<>();

    /** Cleared on shutdown so the reader loops end. */
    private volatile boolean running = true;

    /**
     * One opened device: its source and the descriptor to release.
     *
     * @param source the scanner source
     * @param fd the open file descriptor
     */
    private record OpenDevice(ScannerSource source, int fd) {
    }

    /**
     * Discovers the scanners and starts one reader thread per device at boot.
     *
     * @param event the application startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOGGER.info("Lecteur douchette desactive (pos.scanner.enabled=false).");
            return;
        }
        for (ScannerDiscovery.DiscoveredDevice device : new ScannerDiscovery(namePatterns).discover()) {
            startReader(toSource(device));
        }
    }

    /**
     * Builds the source that reads a discovered device in its channel's mode.
     *
     * @param device the discovered device
     * @return the matching scanner source
     */
    private ScannerSource toSource(ScannerDiscovery.DiscoveredDevice device) {
        return switch (device.channel()) {
            case EVDEV -> new EvdevKeyboardSource(device.path(), this::pushCode);
            case HIDRAW -> new HidrawScannerSource(device.path(), this::pushCode);
        };
    }

    /**
     * Opens one source and starts its blocking reader thread. A source that
     * cannot be opened is logged by the source and skipped — one unreadable
     * scanner never stops the others or the register.
     *
     * @param source the scanner source to read
     */
    private void startReader(ScannerSource source) {
        int fd = source.open();
        if (fd < 0) {
            return;
        }
        openDevices.add(new OpenDevice(source, fd));
        Thread reader = new Thread(() -> readLoop(source, fd), "scanner-reader-" + source.path());
        reader.setDaemon(true);
        readers.add(reader);
        reader.start();
        LOGGER.infof("Lecture douchette active sur %s", source.path());
    }

    /**
     * The per-device read loop: blocks on {@code read(2)} and hands each chunk
     * to the source to decode. Ends when {@link #running} clears or the device
     * reports end/failure.
     *
     * @param source the scanner source
     * @param fd the open file descriptor
     */
    private void readLoop(ScannerSource source, int fd) {
        byte[] buffer = new byte[source.readSize()];
        while (running) {
            int read = LinuxInput.C.read(fd, buffer, buffer.length);
            if (read <= 0) {
                if (running) {
                    LOGGER.warnf("Fin de lecture douchette %s (read=%d)", source.path(), read);
                }
                return;
            }
            source.decode(buffer, read, this::pushCode);
        }
    }

    /**
     * Pushes one completed code to the loopback scan intake. A push failure is
     * logged and dropped — a blip on the loopback must never throw on a reader
     * thread and kill it.
     *
     * @param code the decoded barcode
     */
    void pushCode(String code) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sinkUrl))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(code))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOGGER.errorf("Envoi du code douchette '%s' echoue: %s", code, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Releases every opened device on shutdown so a redeploy leaves nothing
     * held.
     */
    @PreDestroy
    void stop() {
        running = false;
        for (OpenDevice device : openDevices) {
            device.source().close(device.fd());
        }
        openDevices.clear();
        readers.clear();
    }
}
