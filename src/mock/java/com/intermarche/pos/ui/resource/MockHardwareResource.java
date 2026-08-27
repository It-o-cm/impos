package com.intermarche.pos.ui.resource;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The EMBEDDED HARDWARE SIMULATOR: the scale, the drawer, the customer
 * display, the printer and the virtual payment terminal, driven over HTTP so
 * that a register with no peripheral attached can still be sold, tested and
 * demonstrated.
 * <p>
 * It lives in its own source root ({@code src/mock/java}) but is compiled
 * WITH the application, so it answers in DEV — a simulator that only existed
 * during a test run could not be demonstrated, and half the demo script
 * (weighing, drawer, receipt) goes through it. What keeps it out of a store
 * is the BUILD-TIME profile filter below, the same one that governs the
 * referential seed: the class is compiled everywhere, the bean exists only in
 * dev and test, and a production jar exposes none of these endpoints.
 */
@Path("/api/hardware")
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class MockHardwareResource {

    /**
     * The register state — needed to relay the virtual terminal's decisions.
     * <p>
     * WHY THESE TWO ENDPOINTS LIVE HERE: JAX-RS selects the resource CLASS by
     * the most specific literal path first. This simulator is
     * {@code @Path("/api/hardware")} while PosHardwareResource is
     * {@code @Path("/")} with absolute method paths, so as soon as the
     * simulator is on the classpath (test scope) EVERY /api/hardware/** URI
     * resolves against this class — and /api/hardware/tpe/accept|refuse,
     * having no match here, answered 404. The TPE control surface was
     * therefore unreachable under test. These two pass-throughs restore it.
     * <p>
     * The beans are resolved AT CALL TIME through Arc: field injection came
     * back null here (this simulator lives in a separate source root and is
     * not treated as a managed bean for injection), and a null field is a
     * 500 instead of a relay.
     *
     * @return the managed PosState instance
     */
    private com.intermarche.pos.ui.PosState posState() {
        return io.quarkus.arc.Arc.container()
                .instance(com.intermarche.pos.ui.PosState.class).get();
    }

    /**
     * Resolves the payment service at call time (same reason as posState()).
     *
     * @return the managed PaymentService instance
     */
    private com.intermarche.pos.ui.payment.PaymentService paymentService() {
        return io.quarkus.arc.Arc.container()
                .instance(com.intermarche.pos.ui.payment.PaymentService.class).get();
    }

    private final Random random = new Random();

    // --- États du Matériel Simulé ---
    private final AtomicReference<Double> pendingWeight = new AtomicReference<>(null);
    private final AtomicReference<String> currentDisplayText = new AtomicReference<>("BIENVENUE");
    private final AtomicBoolean isDrawerOpen = new AtomicBoolean(false);

    // --- Imprimante ---
    private final AtomicReference<String> printerBuffer = new AtomicReference<>(""); // Contenu du ticket
    private final AtomicBoolean paperPresent = new AtomicBoolean(true); // Y a-t-il du papier ?

    // --- Pannes matérielles injectables (capteur tiroir / afficheur) ---
    private final AtomicBoolean drawerSensorAlive = new AtomicBoolean(true); // Le capteur tiroir répond-il ?
    private final AtomicBoolean displayAlive = new AtomicBoolean(true); // L'afficheur client répond-il ?

    // --- POIDS (BALANCE) ---

    @POST
    @Path("/set-weight")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setManualWeight(String weightStr) {
        try {
            double w = Double.parseDouble(weightStr.replace(',', '.'));
            pendingWeight.set(w);
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Format invalide").build();
        }
    }

    @GET
    @Path("/weight")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getWeight() {
        Double manualWeight = pendingWeight.getAndSet(null);
        String formattedWeight;
        if (manualWeight != null) {
            formattedWeight = String.format("%.3f", manualWeight);
        } else {
            double weight = 0.5 + (random.nextDouble() * 4.5);
            formattedWeight = String.format("%.3f", weight);
        }
        return Response.ok(formattedWeight).build();
    }

    // --- AFFICHEUR CLIENT ---

    @POST
    @Path("/display")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setDisplay(String text) {
        if (!displayAlive.get()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("DISPLAY_ERROR: OFFLINE")
                    .build();
        }
        currentDisplayText.set(text);
        return Response.ok().build();
    }

    /**
     * Simule la coupure / le rétablissement de l'afficheur client (panne
     * matérielle). Tant qu'il est coupé, {@code POST /display} répond 503 :
     * côté caisse, {@code HardwareService.displayMessage} avale l'erreur en
     * silence et la vente continue.
     */
    @POST
    @Path("/display/toggle")
    public Response toggleDisplay() {
        displayAlive.set(!displayAlive.get());
        return Response.ok("Display status: " + (displayAlive.get() ? "ALIVE" : "OFFLINE")).build();
    }

    @GET
    @Path("/display")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDisplay() {
        return Response.ok(currentDisplayText.get()).build();
    }

    // --- TIROIR CAISSE ---

    @POST
    @Path("/drawer/open")
    public Response openDrawer() {
        isDrawerOpen.set(true);
        return Response.ok().build();
    }

    @POST
    @Path("/drawer/close")
    public Response closeDrawer() {
        isDrawerOpen.set(false);
        return Response.ok().build();
    }

    @GET
    @Path("/drawer/status")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getDrawerStatus() {
        if (!drawerSensorAlive.get()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("DRAWER_SENSOR_ERROR: OFFLINE")
                    .build();
        }
        return Response.ok(isDrawerOpen.get() ? "OPEN" : "CLOSED").build();
    }

    /**
     * Simule la mort / le rétablissement du capteur de tiroir (panne
     * matérielle). Tant qu'il est mort, {@code GET /drawer/status} répond 503 :
     * côté caisse, {@code HardwareService.isDrawerOpen} répond FALSE (sécurité),
     * la garde tiroir est donc désactivée et n'enferme pas la caisse.
     */
    @POST
    @Path("/drawer/toggle-sensor")
    public Response toggleDrawerSensor() {
        drawerSensorAlive.set(!drawerSensorAlive.get());
        return Response.ok("Drawer sensor: " + (drawerSensorAlive.get() ? "ALIVE" : "OFFLINE")).build();
    }

    // --- IMPRIMANTE TICKET ---

    /**
     * Simule l'impression d'un texte.
     * Le texte s'ajoute au buffer visible sur l'écran de simulation.
     */
    @POST
    @Path("/printer/print")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response printTicket(String content) {
        if (!paperPresent.get()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("PRINTER_ERROR: NO_PAPER")
                    .build();
        }
        // On ajoute le contenu au buffer existant
        printerBuffer.updateAndGet(current -> current + content + "\n");
        return Response.ok().build();
    }

    /**
     * Retourne le contenu actuel du buffer d'impression.
     */
    @GET
    @Path("/printer/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPrintedContent() {
        return Response.ok(printerBuffer.get()).build();
    }

    /**
     * Simule la coupe du papier et la remise à zéro du ticket.
     */
    @POST
    @Path("/printer/cut")
    public Response cutPaper() {
        // Au lieu d'effacer, on ajoute un marqueur visuel de coupe
        String cutLine = "\n- - - - - - - - - - - - - - -\n       [ COUPE PAPIER ]\n- - - - - - - - - - - - - - -\n\n";
        printerBuffer.updateAndGet(current -> current + cutLine);
        return Response.ok().build();
    }

    /**
     * Retourne l'état de l'imprimante (OK ou NO_PAPER).
     */
    @GET
    @Path("/printer/status")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPrinterStatus() {
        return Response.ok(paperPresent.get() ? "OK" : "NO_PAPER").build();
    }

    /**
     * Inverse l'état du papier pour simuler une panne.
     */
    @POST
    @Path("/printer/toggle-paper")
    public Response togglePaper() {
        paperPresent.set(!paperPresent.get());
        return Response.ok("Paper status: " + (paperPresent.get() ? "PRESENT" : "ABSENT")).build();
    }

    /**
     * Efface complètement le buffer d'impression.
     */
    @POST
    @Path("/printer/clear")
    public Response clearPrinter() {
        printerBuffer.set("");
        return Response.ok().build();
    }

    // --- TPE VIRTUEL (relais : voir la note d'ombrage JAX-RS ci-dessus) ---

    /**
     * Relays the terminal status poll to the register — mirror of
     * {@code PosHardwareResource.tpeStatus()}. Without this relay the JAX-RS
     * shadowing (see the note above) sends the simulator's 1-second
     * {@code GET /api/hardware/tpe} poll to a 404: the pending amount never
     * shows and the ACCEPT/REFUSE buttons stay disabled.
     *
     * @return a JSON map with the pending flag and the formatted amount
     */
    @GET
    @Path("/tpe")
    @Produces(MediaType.APPLICATION_JSON)
    public java.util.Map<String, Object> tpeStatus() {
        com.intermarche.pos.ui.PosState state = posState();
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        boolean pending = state.payment.pendingCardAmount != null;
        result.put("pending", pending);
        result.put("amount", pending
                ? state.payment.pendingCardAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        return result;
    }

    /**
     * Relays the terminal's ACCEPT decision to the register.
     *
     * @return 200, or 409 when no card request is pending
     */
    @POST
    @Path("/tpe/accept")
    public Response tpeAccept() {
        com.intermarche.pos.ui.PosState state = posState();
        if (state.payment.pendingCardAmount == null) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        paymentService().confirmPendingCard(state);
        return Response.ok().build();
    }

    /**
     * Relays the terminal's REFUSE decision to the register.
     *
     * @return 200, or 409 when no card request is pending
     */
    @POST
    @Path("/tpe/refuse")
    public Response tpeRefuse() {
        com.intermarche.pos.ui.PosState state = posState();
        if (state.payment.pendingCardAmount == null) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        paymentService().refusePendingCard(state);
        return Response.ok().build();
    }
}
