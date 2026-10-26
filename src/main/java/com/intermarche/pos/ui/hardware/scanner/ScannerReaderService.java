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

    /**
     * The intake the customer-side scanner pushes to (BO-03-09-18): fidelity
     * cards and coupons only, never an article.
     */
    @ConfigProperty(name = "pos.scanner.customer-sink-url",
            defaultValue = "http://127.0.0.1:8080/api/pos/scan/customer")
    String customerSinkUrl;

    /** The administered activation, driver and device names (BO-03-09-16/17/18). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService settings;

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
        // BO-03-09-16: the activation administered for this register.
        if (!settings.hardwareEnabled("scanner")) {
            LOGGER.info("Lecteur douchette desactive par le back-office (hardware.scanner.enabled).");
            return;
        }
        // BO-03-09-17: an OPOS driver is held by the hardware module, which
        // pushes the codes to the same intake; the register reads nothing itself.
        String driver = settings.scannerDriver();
        if ("OPOS".equals(driver)) {
            LOGGER.info("Scanner pilote en OPOS par le module materiel.");
            return;
        }
        List<String> patterns = patterns(settings.scannerNamePatterns(), namePatterns);
        List<String> customer = settings.secondScannerEnabled()
                ? patterns(settings.secondScannerNamePatterns(), List.of()) : List.of();
        List<String> all = new ArrayList<>(patterns);
        all.addAll(customer);
        for (ScannerDiscovery.DiscoveredDevice device : new ScannerDiscovery(all).discover()) {
            if (!"AUTO".equals(driver) && !driver.equals(device.channel().name())) {
                continue;
            }
            // BO-03-09-18: the customer-side scanner reads at the same time as
            // the first, into its own intake.
            boolean customerSide = matches(device.name(), customer);
            startReader(toSource(device, customerSide ? this::pushCustomerCode : this::pushCode));
        }
    }

    /**
     * The device-name fragments administered, or the deployment's own when
     * none is administered.
     *
     * @param administered the semicolon list posed by the back office
     * @param fallback the deployment's list
     * @return the fragments, lower-cased
     */
    static List<String> patterns(String administered, List<String> fallback) {
        List<String> patterns = new ArrayList<>();
        for (String part : administered.split("[;,]")) {
            if (!part.isBlank()) {
                patterns.add(part.trim().toLowerCase());
            }
        }
        return patterns.isEmpty() ? fallback : patterns;
    }

    /**
     * Tells whether a device name carries one of the fragments.
     *
     * @param name the device name, possibly null
     * @param fragments the fragments, lower-cased
     * @return true when one of them is in the name
     */
    static boolean matches(String name, List<String> fragments) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String fragment : fragments) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the source that reads a discovered device in its channel's mode.
     *
     * @param device the discovered device
     * @param sink where the decoded codes go
     * @return the matching scanner source
     */
    private ScannerSource toSource(ScannerDiscovery.DiscoveredDevice device,
                                   java.util.function.Consumer<String> sink) {
        return switch (device.channel()) {
            case EVDEV -> new EvdevKeyboardSource(device.path(), sink);
            case HIDRAW -> new HidrawScannerSource(device.path(), sink);
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
        push(sinkUrl, code);
    }

    /**
     * Pushes one code read by the CUSTOMER-SIDE scanner to its own intake,
     * which takes fidelity cards and coupons only (BO-03-09-18).
     *
     * @param code the decoded barcode
     */
    void pushCustomerCode(String code) {
        push(customerSinkUrl, code);
    }

    /**
     * Pushes one code to an intake of the register.
     *
     * @param url the intake
     * @param code the decoded barcode
     */
    private void push(String url, String code) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
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
