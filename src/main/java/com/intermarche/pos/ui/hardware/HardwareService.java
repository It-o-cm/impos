package com.intermarche.pos.ui.hardware;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
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

    /** The register state, read for the customer screen's own liveness. */
    @Inject
    com.intermarche.pos.ui.PosState state;

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
     * idle greeting (what it shows anyway). The printer has no harmless
     * probe in the hardware contract (printing or cutting are physical
     * acts), so its status is inherited: FAILED when not one probe got an
     * answer (no hardware bridge at all), presumed AVAILABLE otherwise —
     * the day the bridge exposes {@code /printer/status}, probe it here
     * instead.
     *
     * @return the four device statuses, in display order
     */
    public java.util.List<DeviceStatus> probeDevices() {
        Availability scale = probe(() -> hardwareClient.getWeight());
        Availability drawer = probe(() -> hardwareClient.getDrawerStatus());
        Availability display = customerScreenPresent()
                ? Availability.AVAILABLE
                : probe(() -> hardwareClient.setDisplay("BONJOUR"));
        boolean bridgeAnswered = scale != Availability.FAILED
                || drawer != Availability.FAILED
                || display != Availability.FAILED;
        return java.util.List.of(
                new DeviceStatus("BALANCE", scale),
                new DeviceStatus("TIROIR-CAISSE", drawer),
                new DeviceStatus("AFFICHEUR CLIENT", display),
                new DeviceStatus("IMPRIMANTE TICKETS",
                        bridgeAnswered ? Availability.AVAILABLE : Availability.FAILED));
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
        try {
            String weightStr = hardwareClient.getWeight();
            return Double.parseDouble(weightStr.replace(',', '.'));
        } catch (Exception e) {
            LOGGER.error("Erreur de communication avec la balance", e);
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
    }

    /**
     * Fires the drawer-opening pulse.
     */
    public void openDrawer() {
        try {
            hardwareClient.openDrawer();
            LOGGER.info("Ordre d'ouverture du tiroir envoyé.");
        } catch (Exception e) {
            LOGGER.error("Erreur lors de l'ouverture du tiroir", e);
        }
    }

    /**
     * Reads the physical drawer state for the drawer guard filter.
     *
     * @return true when the drawer answers OPEN; false otherwise, including
     *         on failure — a dead sensor never locks the register
     */
    public boolean isDrawerOpen() {
        try {
            String status = hardwareClient.getDrawerStatus();
            return "OPEN".equalsIgnoreCase(status);
        } catch (Exception e) {
            LOGGER.error("Erreur de communication avec le tiroir", e);
            return false; // Sécurité : en cas d'erreur, on ne bloque pas la caisse
        }
    }

    /**
     * Sends a formatted receipt to the printer.
     *
     * @param content the 42-column formatted text
     */
    public void printReceipt(String content) {
        try {
            hardwareClient.printTicket(content);
            LOGGER.info("Ticket envoyé à l'imprimante.");
        } catch (Exception e) {
            LOGGER.error("Erreur d'impression", e);
        }
    }

    /**
     * Cuts the printer paper.
     */
    public void cutPaper() {
        try {
            hardwareClient.cutPaper();
            LOGGER.info("Coupe papier envoyée.");
        } catch (Exception e) {
            LOGGER.error("Erreur coupe papier", e);
        }
    }
}
