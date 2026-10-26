package com.intermarche.pos.ui.hardware;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Facade over the hardware REST client, and the place where the register's
 * DEGRADED-MODE PHILOSOPHY lives — in two tiers, deliberately different:
 * <ul>
 * <p>
 * The CUSTOMER DISPLAY is the one device this facade does not always reach through the
 * hardware bridge: on a till whose customer display is a second SCREEN, there is no
 * peripheral to probe, and the evidence is the page polling itself — see
 * {@link #customerScreenPresent()}.
 * <ul>
 *   <li>AT THE DOOR ({@link #probeDevices()}): FAILING hardware BLOCKS the
 *       register. The unlock gate probes the peripherals and refuses to open
 *       the lane while any is unreachable, showing which ones — a till must
 *       not start selling with no drawer and no printer behind it. A device
 *       the bridge declares ABSENT is not a failure: every till does not
 *       carry a scale or a customer display, and refusing to open a lane for
 *       a peripheral nobody installed would close the shop.</li>
 *   <li>MID-SALE (every other method): a peripheral call swallows its
 *       exceptions — a scale, display, drawer or printer dying DURING a sale
 *       is logged and never blocks the transaction in flight (the fiscal
 *       path cannot hostage-take on a cable). {@link #requestWeighing()}
 *       answers 0.0 on failure (callers treat it as "no weight"), and
 *       {@link #isDrawerOpen()} answers FALSE on failure so a broken drawer
 *       sensor cannot trap the register behind the drawer guard.</li>
 * </ul>
 */
@ApplicationScoped
public class HardwareService {

    private static final Logger LOGGER = Logger.getLogger(HardwareService.class);

    @Inject
    @RestClient
    HardwareClient hardwareClient;

    /** Administered peripheral activation (LC-13-02-09/-14). */
    @Inject com.intermarche.pos.service.PosSettingsService settings;
    /** Localised device labels (LC-13-02-09/-14). */
    @Inject com.intermarche.pos.ui.Messages i18n;


    /** The register state, read for the customer screen's own liveness. */
    @Inject
    com.intermarche.pos.ui.PosState state;

    /** Keeps every ticket printed, for the store node (LC-08-02-02). */
    @Inject
    PrintoutService printoutService;

    /** The electronic journal the drawer openings are traced in (BO-04-03-07). */
    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** Pushes the administered driver configuration (BO-03-09-11 to -22). */
    @Inject
    HardwareConfigurationService hardwareConfigurationService;

    /** Says whether this register is ADMINISTERED with a customer display. */
    @Inject
    com.intermarche.pos.service.CheckoutPointService checkoutPointService;

    /**
     * How long a weighing waits for an article to be PUT ON THE PLATE, in
     * milliseconds.
     *
     * <p>THE ORDER OF THE TWO GESTURES WAS FORCED, and backwards. The weighing
     * is an instantaneous read: choosing the article first and laying the fruit
     * down second read an empty plate, answered {@code POIDS INVALIDE} and
     * created nothing, so the operator learned to lay the fruit down FIRST and
     * choose afterwards — the reverse of what the screen asks and of what the
     * field expert reported. Waiting a moment for the plate to be loaded makes
     * both orders work, and costs nothing when the article is already there: the
     * first read returns and the wait never starts.
     */
    @ConfigProperty(name = "balance.weighing-wait-ms", defaultValue = "4000")
    long weighingWaitMs;

    /** How often the plate is re-read while the weighing waits, in milliseconds. */
    @ConfigProperty(name = "balance.weighing-poll-ms", defaultValue = "200")
    long weighingPollMs;

    /**
     * How recently the customer screen must have polled its page to count as present.
     * <p>
     * The page polls once a second, so anything under a few seconds would blink on a
     * slow reload and anything over a minute would keep declaring a screen that was
     * unplugged long ago.
     */
    private static final long CUSTOMER_SCREEN_FRESHNESS_MS = 15_000L;

    /** Bridge error code naming a role with no device bound to it. */
    private static final String NO_DEVICE_BOUND = "NO_DEVICE_BOUND";

    /**
     * Header through which the bridge names the cause of an error answer.
     * <p>
     * The code is in the body too, but the body cannot be relied on here:
     * the REST client hands its exception a SANITIZED response — status and
     * headers kept, entity dropped — so reading the entity of a failed call
     * yields nothing. The header is the only channel that survives, which is
     * why an unbound role was read as a breakdown before it existed.
     */
    private static final String STATUS_HEADER = "X-Hardware-Status";

    /**
     * How one peripheral answered the entry probe. The distinction that
     * matters at the door is ABSENT versus FAILED: a till without a scale
     * must open, a till whose scale is expected and mute must not.
     */
    public enum Availability {

        /** The device answered its probe: present and working. */
        AVAILABLE,

        /**
         * There is no such device on this till, and the bridge said so
         * explicitly (501, or 503 {@code NO_DEVICE_BOUND}).
         */
        ABSENT,

        /**
         * The device did not answer — unreachable bridge, contract drift or
         * a bound device in error. This is the only state that keeps the
         * lane closed.
         */
        FAILED
    }

    /**
     * One peripheral's availability as seen from the entry probe.
     *
     * @param name the operator-facing device name (French, upper case)
     * @param availability how the device answered its probe
     */
    public record DeviceStatus(String name, Availability availability) {

        /**
         * Tells whether the device answered its probe.
         *
         * @return true when the device is present and working
         */
        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        /**
         * Tells whether this till simply has no such device.
         *
         * @return true when the bridge declared the role unbound
         */
        public boolean absent() {
            return availability == Availability.ABSENT;
        }

        /**
         * Tells whether the device is missing while it should be there.
         *
         * @return true when the device failed its probe
         */
        public boolean failed() {
            return availability == Availability.FAILED;
        }
    }

    /**
     * Probes the peripherals for the unlock gate and returns one status per
     * device. Read-only or harmless probes only: the scale is read
     * ({@code GET /weight}), the drawer sensor is read
     * ({@code GET /drawer/status}), the customer display is probed with the
     * idle greeting (what it shows anyway). LC-13-02-09/-14: printer flags
     * and scanner transport status are probed independently, and administered
     * deactivation is distinct from a bound device's failure.
     *
     * @return the five device statuses, in display order
     */
    public java.util.List<DeviceStatus> probeDevices() {
        LOGGER.info("Entering method probeDevices");
        // BO-03-09-11/14/16/21/22: the driver configuration administered for
        // this register reaches the hardware module before the lane opens.
        if (hardwareConfigurationService != null) {
            hardwareConfigurationService.push();
        }
        Availability scale = settings.hardwareEnabled("scale")
                ? probe(() -> hardwareClient.getWeight()) : Availability.ABSENT;
        // BO-03-09-11: a drawer whose driver is deactivated is not a missing drawer.
        Availability drawer = settings.hardwareEnabled("drawer")
                ? probe(() -> hardwareClient.getDrawerStatus()) : Availability.ABSENT;
        // BO-03-09-13: a point administered WITHOUT a customer display is not a
        // point whose display is missing. Probing it would answer FAILED or
        // ABSENT for a peripheral nobody installed, and the unlock gate would
        // name it at every prise de poste.
        Availability display;
        if (!checkoutPointService.expectsCustomerDisplay()) {
            display = Availability.ABSENT;
        } else if (customerScreenPresent()) {
            display = Availability.AVAILABLE;
        } else {
            display = probe(() -> hardwareClient.setDisplay("BONJOUR"));
        }
        Availability printer = settings.hardwareEnabled("printer") ? probe(() -> {
            if (!"OK".equals(hardwareClient.getPrinterStatus())) {
                throw new IllegalStateException("PRINTER_ERROR");
            }
        }) : Availability.ABSENT;
        state.scannerDisabled = !settings.hardwareEnabled("scanner");
        Availability scanner = state.scannerDisabled ? Availability.ABSENT
                : probe(() -> hardwareClient.maintenance("scanner", "status", ""));
        remember("hardware.printer", printer);
        remember("hardware.scale", scale);
        remember("hardware.scanner", scanner);
        LOGGER.info("Exiting method probeDevices");
        return java.util.List.of(
                new DeviceStatus("BALANCE", scale),
                new DeviceStatus("TIROIR-CAISSE", drawer),
                new DeviceStatus("AFFICHEUR CLIENT", display),
                new DeviceStatus("IMPRIMANTE TICKETS", printer),
                new DeviceStatus(i18n.get("hardware.scanner"), scanner));
    }

    /** Refreshes LC-13-02-12 scanner health while the lane remains usable. */
    public void probeScanner() {
        state.scannerDisabled = !settings.hardwareEnabled("scanner");
        Availability scanner = state.scannerDisabled ? Availability.ABSENT
                : probe(() -> hardwareClient.maintenance("scanner", "status", ""));
        remember("hardware.scanner", scanner);
    }

    /**
     * Retains a peripheral indicator for the selling and payment pages.
     * @param role the device's message-bundle key
     * @param availability the independently observed state (LC-13-02-09/-14)
     */
    private void remember(String role, Availability availability) {
        String previous = state.peripheralAvailability.put(role, availability.name());
        if (!availability.name().equals(previous)) {
            state.touch();
        }
    }

    /**
     * Classifies a failed operation without confusing a missing role with a fault.
     * @param failure the client failure
     * @return absence or failure (LC-13-02-09/-14)
     */
    private Availability failedOperation(Exception failure) {
        if (failure instanceof WebApplicationException web) {
            return declaredAbsent(web.getResponse()) ? Availability.ABSENT : Availability.FAILED;
        }
        return Availability.FAILED;
    }

    /**
     * Tells whether a customer SCREEN is showing its page right now.
     * <p>
     * A customer display is not always a line display on the hardware bus: on this park
     * it is often a second screen showing {@code /customer}, which no peripheral probe
     * can ever find. Such a screen announces itself by polling its own page once a
     * second, so a recent poll IS the evidence — and it takes precedence over the line
     * display probe, since a till showing the page has a working customer display
     * whatever the bus says.
     *
     * @return true when the customer page was polled recently enough
     */
    private boolean customerScreenPresent() {
        long seen = state.customerDisplaySeenAt;
        return seen > 0 && System.currentTimeMillis() - seen <= CUSTOMER_SCREEN_FRESHNESS_MS;
    }

    /**
     * Runs one probe call and classifies its outcome, without logging a
     * stack trace — an unreachable device is the state the probe exists to
     * report, not an incident. An HTTP answer naming an unbound role is
     * read as ABSENT; anything else that fails is a breakdown.
     *
     * @param call the client call to attempt
     * @return AVAILABLE when the call succeeded, ABSENT when the bridge
     *         declared the role unbound, FAILED otherwise
     */
    private Availability probe(Runnable call) {
        try {
            call.run();
            return Availability.AVAILABLE;
        } catch (WebApplicationException e) {
            return declaredAbsent(e.getResponse()) ? Availability.ABSENT : Availability.FAILED;
        } catch (Exception e) {
            return Availability.FAILED;
        }
    }

    /**
     * Reads a bridge error answer and tells whether it declares the role
     * unbound rather than broken. Two shapes say "there is nothing here":
     * 501, the endpoint not being implemented for a device this till does
     * not drive, and 503 {@code NO_DEVICE_BOUND}, the bridge naming the
     * absence. Every other status — an unreachable bridge, a 404 on a
     * drifted contract, a 503 carrying a device error code — is a failure.
     *
     * @param response the error response carried by the client exception
     * @return true when the response declares the role unbound
     */
    private boolean declaredAbsent(Response response) {
        if (response == null) {
            return false;
        }
        if (response.getStatus() == Response.Status.NOT_IMPLEMENTED.getStatusCode()) {
            return true;
        }
        if (response.getStatus() != Response.Status.SERVICE_UNAVAILABLE.getStatusCode()) {
            return false;
        }
        return errorCode(response).startsWith(NO_DEVICE_BOUND);
    }

    /**
     * Reads the machine-readable code an error answer carries: the header
     * first, which always survives the client's sanitizing, then the body,
     * which a curl-driven bridge or a test may be the only one to fill.
     *
     * @param response the error response to read
     * @return the code, empty when absent or unreadable
     */
    private String errorCode(Response response) {
        String header = response.getHeaderString(STATUS_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        try {
            String body = response.readEntity(String.class);
            return body == null ? "" : body.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Requests a weighing from the scale, tolerating the French decimal
     * comma.
     *
     * @return the weight in kilograms, or 0.0 on any failure
     */
    public double requestWeighing() {
        LOGGER.info("Entering method requestWeighing");
        if (!settings.hardwareEnabled("scale")) {
            remember("hardware.scale", Availability.ABSENT);
            return 0.0;
        }
        // LC-13-02-14: a known failed scale is not retried for every weighed article.
        if (Availability.FAILED.name().equals(state.peripheralAvailability.get("hardware.scale"))) {
            return 0.0;
        }
        try {
            String weightStr = hardwareClient.getWeight();
            LOGGER.info("Exiting method requestWeighing");
            double weight = Double.parseDouble(weightStr.replace(',', '.'));
            remember("hardware.scale", Availability.AVAILABLE);
            return weight;
        } catch (Exception e) {
            remember("hardware.scale", failedOperation(e));
            LOGGER.error("Erreur de communication avec la balance", e);
            LOGGER.info("Exiting method requestWeighing");
            return 0.0;
        }
    }

    /**
     * Requests a weighing and WAITS for the article to be laid on the plate.
     *
     * <p>Same read as {@link #requestWeighing(java.math.BigDecimal)}, repeated
     * until the plate carries something or the wait runs out. An article already
     * on the plate is weighed on the first read, so this costs nothing in the
     * habitual order; an empty plate gives the operator the few seconds they need
     * to lay the fruit down after choosing it.
     *
     * <p>An interrupted wait stops at once and answers what the plate last read:
     * a register being shut down does not hold the sale thread on a scale.
     *
     * @param tareKg the software tare in kilograms, or null for none
     * @return the NET weight in kilograms, or 0.0 when nothing was laid down
     */
    public double awaitWeighing(java.math.BigDecimal tareKg) {
        LOGGER.info("Entering method awaitWeighing with tareKg: " + tareKg);
        long deadline = System.currentTimeMillis() + Math.max(0L, weighingWaitMs);
        double weight = requestWeighing(tareKg);
        while (weight <= 0 && System.currentTimeMillis() < deadline) {
            // LC-13-02-14: a broken or disabled scale does not incur the empty-plate wait.
            if (!Availability.AVAILABLE.name().equals(state.peripheralAvailability.get("hardware.scale"))) {
                return weight;
            }
            try {
                Thread.sleep(Math.max(1L, weighingPollMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.info("Exiting method awaitWeighing");
                return weight;
            }
            weight = requestWeighing(tareKg);
        }
        LOGGER.info("Exiting method awaitWeighing");
        return weight;
    }

    /**
     * Requests a weighing from the scale AFTER a software tare (LC-14-01): the
     * tare is sent to the scale, which prioritises it over any hardware tare
     * (LC-14-01-06) and returns the NET weight — the register records that net
     * as-is and never re-deducts. A null or non-positive tare is sent as "0",
     * i.e. a plain weighing.
     *
     * @param tareKg the software tare in kilograms, or null for none
     * @return the NET weight in kilograms, or 0.0 on any failure
     */
    public double requestWeighing(java.math.BigDecimal tareKg) {
        LOGGER.info("Entering method requestWeighing with tareKg: " + tareKg);
        String tare = (tareKg == null || tareKg.signum() <= 0)
                ? "0" : tareKg.toPlainString();
        if (!settings.hardwareEnabled("scale")) {
            remember("hardware.scale", Availability.ABSENT);
            return 0.0;
        }
        // LC-13-02-14: a known failed scale is not retried for every weighed article.
        if (Availability.FAILED.name().equals(state.peripheralAvailability.get("hardware.scale"))) {
            return 0.0;
        }
        try {
            String weightStr = hardwareClient.getWeightWithTare(tare);
            LOGGER.info("Exiting method requestWeighing");
            double weight = Double.parseDouble(weightStr.replace(',', '.'));
            remember("hardware.scale", Availability.AVAILABLE);
            return weight;
        } catch (Exception e) {
            remember("hardware.scale", failedOperation(e));
            LOGGER.error("Erreur de communication avec la balance", e);
            LOGGER.info("Exiting method requestWeighing");
            return 0.0;
        }
    }

    /**
     * Shows a message on the customer display.
     * <p>
     * TWO DISPLAYS, ONE CALL. The message is first recorded on the register state, from
     * where the customer PAGE picks it up on its next poll — that page is this till's
     * customer display and no line display exists to write to. It is then still pushed
     * to the bridge, for a till that does have one.
     * <p>
     * A bridge that declares the role unbound (501, or 503 {@code NO_DEVICE_BOUND}) is
     * NOT logged as an error: it is the normal answer of a till whose customer display
     * is a screen, and logging a stack trace for every amount shown buries the real
     * incidents under noise.
     *
     * @param message the message to display
     */
    public void displayMessage(String message) {
        LOGGER.info("Entering method displayMessage with message: " + message);
        state.customerMessage = message == null ? "" : message;
        state.touch();
        try {
            hardwareClient.setDisplay(message);
        } catch (WebApplicationException e) {
            if (!declaredAbsent(e.getResponse())) {
                LOGGER.error("Erreur d'affichage", e);
            }
        } catch (Exception e) {
            LOGGER.error("Erreur d'affichage", e);
        }
        LOGGER.info("Exiting method displayMessage");
    }

    /**
     * Fires the drawer-opening pulse.
     *
     * <p>THE ONE PLACE EVERY OPENING GOES THROUGH — payment, take-over, period
     * close, forced opening — so the training-mode rule is enforced here and
     * nowhere else (BO-10-02-12): a register in training mode whose shop
     * keeps the drawer shut there sends no pulse at all, whichever gesture
     * asked for it.
     */
    public void openDrawer() {
        LOGGER.info("Entering method openDrawer");
        if (state != null && state.trainingMode && settings != null
                && !settings.drawerOpenInTraining()) {
            LOGGER.info("Tiroir laissé fermé : mode école (BO-10-02-12).");
            LOGGER.info("Exiting method openDrawer");
            return;
        }
        // BO-03-09-11: no pulse toward a drawer whose driver is deactivated.
        if (settings != null && !settings.hardwareEnabled("drawer")) {
            LOGGER.info("Exiting method openDrawer");
            return;
        }
        try {
            hardwareClient.openDrawer();
            LOGGER.info("Ordre d'ouverture du tiroir envoyé.");
        } catch (Exception e) {
            LOGGER.error("Erreur lors de l'ouverture du tiroir", e);
        }
        traceDrawerOpening();
        LOGGER.info("Exiting method openDrawer");
    }

    /**
     * Journals the opening pulse (BO-04-03-07): the real-time watch of the back
     * office shows every drawer opening, whatever gesture asked for it. A
     * journal that cannot be written never keeps the drawer shut.
     */
    private void traceDrawerOpening() {
        if (technicalEventService == null) {
            return;
        }
        try {
            technicalEventService.record(com.intermarche.pos.domain.session.TechnicalEvent.EventType.DRAWER_OPENED,
                    "Ouverture du tiroir", state == null ? null : state.auth.operatorBadgeId, null, null, null);
        } catch (RuntimeException e) {
            LOGGER.warn("Ouverture du tiroir non journalisée", e);
        }
    }

    /**
     * Reads the physical drawer state for the drawer guard filter.
     *
     * @return true when the drawer answers OPEN; false otherwise, including
     *         on failure — a dead sensor never locks the register
     */
    public boolean isDrawerOpen() {
        LOGGER.info("Entering method isDrawerOpen");
        // BO-03-09-11: a deactivated drawer is held closed, never read.
        if (settings != null && !settings.hardwareEnabled("drawer")) {
            LOGGER.info("Exiting method isDrawerOpen");
            return false;
        }
        try {
            String status = hardwareClient.getDrawerStatus();
            LOGGER.info("Exiting method isDrawerOpen");
            return "OPEN".equalsIgnoreCase(status);
        } catch (Exception e) {
            LOGGER.error("Erreur de communication avec le tiroir", e);
            LOGGER.info("Exiting method isDrawerOpen");
            return false; // Sécurité : en cas d'erreur, on ne bloque pas la caisse
        }
    }

    /**
     * Sends a formatted receipt to the printer.
     *
     * @param content the 42-column formatted text
     */
    public void printReceipt(String content) {
        LOGGER.info("Entering method printReceipt");
        // LC-08-02-02: every ticket generated is kept, printer or not.
        printoutService.record(content);
        if (!settings.hardwareEnabled("printer")) {
            remember("hardware.printer", Availability.ABSENT);
            return;
        }
        try {
            if (!"OK".equals(hardwareClient.getPrinterStatus())) {
                throw new IllegalStateException("PRINTER_ERROR");
            }
            hardwareClient.printTicket(content);
            LOGGER.info("Ticket envoyé à l'imprimante.");
            remember("hardware.printer", Availability.AVAILABLE);
        } catch (Exception e) {
            remember("hardware.printer", failedOperation(e));
            LOGGER.error("Erreur d'impression", e);
        }
        LOGGER.info("Exiting method printReceipt");
    }

    /**
     * Records a ticket generated but kept from the printer by a print policy
     * (LC-08-02-02): its copy reaches the store node like a printed one.
     *
     * @param content the 42-column formatted text
     */
    public void recordUnprinted(String content) {
        printoutService.record(content);
    }

    /**
     * Prints one sheet on the SLIP STATION, the register's micr printer
     * (BO-03-04-14): the facturette output of a document goes there, never to
     * the roll. Recorded like every generated paper (LC-08-02-02).
     *
     * @param content the sheet, as formatted text
     */
    public void printSlip(String content) {
        LOGGER.info("Entering method printSlip");
        printoutService.record(content);
        if (!settings.hardwareEnabled("printer")) {
            remember("hardware.printer", Availability.ABSENT);
            LOGGER.info("Exiting method printSlip");
            return;
        }
        try {
            hardwareClient.printSlip(content);
            LOGGER.info("Feuille envoyée à la station facturette.");
            remember("hardware.printer", Availability.AVAILABLE);
        } catch (Exception e) {
            remember("hardware.printer", failedOperation(e));
            LOGGER.error("Erreur d'impression facturette", e);
        }
        LOGGER.info("Exiting method printSlip");
    }

    /**
     * Cuts the printer paper.
     */
    public void cutPaper() {
        LOGGER.info("Entering method cutPaper");
        if (!settings.hardwareEnabled("printer")) {
            remember("hardware.printer", Availability.ABSENT);
            return;
        }
        try {
            if (!"OK".equals(hardwareClient.getPrinterStatus())) {
                throw new IllegalStateException("PRINTER_ERROR");
            }
            hardwareClient.cutPaper();
            LOGGER.info("Coupe papier envoyée.");
            remember("hardware.printer", Availability.AVAILABLE);
        } catch (Exception e) {
            remember("hardware.printer", failedOperation(e));
            LOGGER.error("Erreur coupe papier", e);
        }
        LOGGER.info("Exiting method cutPaper");
    }
}
