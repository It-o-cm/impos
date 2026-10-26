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
 * referential seed: the class is compiled everywhere, the bean exists only
 * under the profiles it names, and a production jar exposes none of these
 * endpoints.
 * <p>
 * THE {@code demo} PROFILE is the shared demonstrator hosted on the cloud:
 * no peripheral there either, and the panel IS the hardware. It is not a
 * third way of running a shop — a real till is built and deployed without it,
 * so what reaches a store is unchanged. Under {@code demo} this bean takes
 * the {@code /api/hardware} path that
 * {@link com.intermarche.pos.ui.hardware.RealHardwareResource} holds under
 * {@code prod}: exactly one of the two ever exists, so the two identical
 * paths never meet.
 */
@Path("/api/hardware")
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test", "demo"})
public class MockHardwareResource {

    /** LC-13-03-01/-06: demo control permits never execute a host command. */
    private String controlToken;
    /** Localised confirmation labels for the current demo permit. */
    private java.util.Map<String, String> controlLabels = java.util.Map.of();
    /** Same five-minute validity as the independent bridge. */
    private long controlExpiry;

    /**
     * Simulates supervisor-approved closed-session permit creation (LC-13-03-01/-06).
     * @param form the register-validated request
     * @return a one-shot demo permit
     */
    @POST
    @Path("/control/arm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized String armWorkstation(jakarta.ws.rs.core.MultivaluedMap<String, String> form) {
        if (!"true".equals(form.getFirst("closed"))) {
            throw new WebApplicationException(409);
        }
        if (!"true".equals(form.getFirst("supervisor"))) {
            throw new ForbiddenException();
        }
        String action = form.getFirst("action");
        if (action == null) {
            throw new BadRequestException();
        }
        if (!java.util.Set.of("APPLICATION", "REBOOT", "SHUTDOWN").contains(action)) {
            throw new BadRequestException();
        }
        String reason = form.getFirst("reason");
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException();
        }
        controlToken = java.util.UUID.randomUUID().toString();
        controlExpiry = System.currentTimeMillis() + 300_000;
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        for (String key : java.util.List.of("title", "confirm", "cancel", "SIMULATED", "CANCELLED")) {
            labels.put(key, java.util.Objects.toString(form.getFirst(key), ""));
        }
        controlLabels = labels;
        return controlToken;
    }

    /** Revokes demo permits before a session opens (LC-13-03-01/-06). */
    @POST
    @Path("/control/revoke")
    public synchronized void revokeWorkstation() {
        controlToken = null;
    }

    /**
     * Shows the separate demo confirmation without executing commands.
     * @param token the previously approved permit
     * @return the localised confirmation form (LC-13-03-01/-06)
     */
    @GET
    @Path("/control")
    @Produces(MediaType.TEXT_HTML)
    public synchronized String controlPage(@QueryParam("token") String token) {
        requireControlToken(token);
        return "<!DOCTYPE html><html><meta charset=\"UTF-8\"><body><h1>"
                + controlHtml(controlLabels.get("title")) + "</h1>"
                + "<form method=\"POST\" action=\"/api/hardware/control/execute\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + token + "\">"
                + "<button name=\"confirmed\" value=\"true\">" + controlHtml(controlLabels.get("confirm"))
                + "</button><button name=\"confirmed\" value=\"false\">"
                + controlHtml(controlLabels.get("cancel")) + "</button></form></body></html>";
    }

    /**
     * Consumes a demo permit; the configured host command is deliberately unused.
     * @param token the approved permit
     * @param confirmed the separate explicit confirmation
     * @return SIMULATED or CANCELLED (LC-13-03-01/-06)
     */
    @POST
    @Path("/control/execute")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public synchronized String executeWorkstation(@FormParam("token") String token,
            @FormParam("confirmed") boolean confirmed) {
        requireControlToken(token);
        controlToken = null;
        String code = confirmed ? "SIMULATED" : "CANCELLED";
        return "<!DOCTYPE html><html><meta charset=\"UTF-8\"><body><p data-result=\""
                + code + "\">" + controlHtml(controlLabels.get(code)) + "</p></body></html>";
    }

    /**
     * Requires an unexpired current permit before any demo confirmation.
     * @param token the supplied token (LC-13-03-01/-06)
     */
    private void requireControlToken(String token) {
        if (controlToken == null) {
            throw new ForbiddenException();
        }
        if (!controlToken.equals(token)) {
            throw new ForbiddenException();
        }
        if (System.currentTimeMillis() >= controlExpiry) {
            throw new ForbiddenException();
        }
    }

    /**
     * Escapes localised labels on the demo confirmation page.
     * @param text the supplied label
     * @return HTML-safe text (LC-13-03-03)
     */
    private static String controlHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }


    /**
     * Simulates maintenance without physical hardware (LC-13-02-01/-07).
     * @param device the peripheral role
     * @param action the maintenance operation
     * @param text the localised printer test text
     * @return the simulated status
     */
    @POST
    @Path("/maintenance/{device}/{action}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public String maintenance(@PathParam("device") String device,
            @PathParam("action") String action, String text) {
        if ("terminal".equals(device)) {
            if (!java.util.Set.of("reset", "test", "status", "login", "logoff").contains(action)) {
                throw new BadRequestException();
            }
            return "state=DONE\nline1=\nline2=\nresult=00\napproved=true\nfailure=\nanswer64=";
        }
        if (!java.util.Set.of("printer", "scanner", "handheld").contains(device)) {
            throw new BadRequestException();
        }
        if (!java.util.Set.of("reset", "test", "status", "stop").contains(action)) {
            throw new BadRequestException();
        }
        if ("printer".equals(device)) {
            if ("test".equals(action)) {
                Response result = printTicket(text);
                if (result.getStatus() >= 400) {
                    throw new ServiceUnavailableException();
                }
            }
        }
        return "active=true\ncode=";
    }


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
     * Resolves the virtual terminal at call time (same reason as
     * posState()): the simulator's decisions fire its transaction callback.
     *
     * @return the managed VirtualTerminalClient instance
     */
    private com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient virtualTerminal() {
        return io.quarkus.arc.Arc.container()
                .instance(com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient.class).get();
    }


    private final Random random = new Random();

    // --- États du Matériel Simulé ---
    private final AtomicReference<Double> pendingWeight = new AtomicReference<>(null);
    private final AtomicReference<String> currentDisplayText = new AtomicReference<>("BIENVENUE");
    private final AtomicBoolean isDrawerOpen = new AtomicBoolean(false);

    // --- Imprimante ---
    private final AtomicReference<String> printerBuffer = new AtomicReference<>(""); // Contenu du ticket

    /** Les feuilles imprimées sur la station facturette simulée (BO-03-04-14). */
    private final AtomicReference<String> slipBuffer = new AtomicReference<>("");
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
        double gross = nextGross();
        return Response.ok(String.format("%.3f", gross)).build();
    }

    /**
     * Weighs after applying a software tare (LC-14-01): the simulated scale
     * takes its gross figure (the preset weight or a random one) and returns
     * the NET weight, gross minus the tare, floored at zero. There is no
     * hardware tare in the simulator, so the software tare simply has priority
     * and is never cumulated (LC-14-01-06); the register records the net as-is.
     *
     * @param tareKg the software tare to apply, in kilograms (French or dot
     *        decimal); a null, blank or invalid value applies no tare
     * @return the NET weight, 3 decimals
     */
    @GET
    @Path("/weight-with-tare")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getWeightWithTare(@QueryParam("tare") String tareKg) {
        double gross = nextGross();
        double tare = parseTare(tareKg);
        double net = Math.max(0.0, gross - tare);
        return Response.ok(String.format("%.3f", net)).build();
    }

    /**
     * Reads the next gross weight the simulated scale reports: the preset
     * weight when one was armed (consumed once), else a random 0,5–5,0 kg.
     *
     * <p>A PRESET ZERO IS AN EMPTY PLATE, and it is not consumed. A weighing no
     * longer reads the scale once: it waits for the article to be laid down and
     * re-reads every few dozen milliseconds ({@code
     * HardwareService.awaitWeighing}). A zero consumed by the first read would
     * be followed by the random fallback, so a plate declared empty would end up
     * weighing an article nobody put on it. Declaring zero therefore keeps the
     * plate empty until another weight is armed, which is what an empty plate
     * does; a positive weight is still consumed by the reading it was armed for.
     *
     * @return the gross weight in kilograms
     */
    private double nextGross() {
        Double armed = pendingWeight.get();
        if (armed == null) {
            return 0.5 + (random.nextDouble() * 4.5);
        }
        if (armed > 0) {
            pendingWeight.set(null);
        }
        return armed;
    }

    /**
     * Parses a tare string, tolerating the French comma; a null, blank or
     * unparsable value means no tare.
     *
     * @param tareKg the tare string, or null
     * @return the tare in kilograms, zero when absent or invalid
     */
    private double parseTare(String tareKg) {
        if (tareKg == null || tareKg.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(tareKg.replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
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

    // --- LECTEUR DE CHÈQUES ---

    /** Simulates LC-07-05-06 front printing in the existing visible printer buffer.
     * @param text rendered cheque front
     * @return printer outcome
     */
    @POST
    @Path("/cheque/front")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response printChequeFront(String text) {
        return printTicket(text);
    }

    /**
     * Simulates the start of a cheque reading (the reader pulls the document in
     * and prints the endorsement). Mirrors {@code HardwareClient.startCheque};
     * without this endpoint every cheque payment 404s at the bridge and is
     * dropped, so no cheque tender could ever settle in the e2e/demo stack.
     *
     * @param endorsement the text printed on the cheque while the reader holds it
     * @return a 200 acknowledgement
     */
    @POST
    @Path("/cheque")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startCheque(String endorsement) {
        return Response.ok().build();
    }

    /**
     * Returns the cheque-reading status the register polls
     * ({@code ChequeReadingService} expects a {@code key=value} block): the
     * simulator reads instantly and successfully, answering {@code state=DONE}
     * with a magnetic line so the pending cheque is registered for its amount.
     *
     * @return the DONE status block with a simulated CMC7 magnetic line
     */
    @GET
    @Path("/cheque")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getChequeStatus() {
        return Response.ok("state=DONE\nraw=CMC7 0000000 0000000000 000000000000").build();
    }

    // --- MONNAYEUR (LC-07-15, LC-13-01-01) ---

    /** Where the simulated deposit stands: IDLE, RUNNING or DONE. */
    private String cashChangerState = "IDLE";

    /** The amount due of the simulated deposit, in cents. */
    private long cashChangerDue;

    /** What the simulated customer has inserted, in cents. */
    private long cashChangerInserted;

    /** What the simulated machine gave back, in cents. */
    private long cashChangerChange;

    /** True when the simulated deposit was cancelled and given back whole. */
    private boolean cashChangerCancelled;

    /** The simulated levels block, as the bridge reports it. */
    private String cashChangerLevels = "status=OK\nfull=OK\nlow=\nhigh=";

    /**
     * Simulates the start of a deposit for the amount due (LC-07-15-01).
     *
     * @param dueCents the amount due, in cents
     * @return a 200 acknowledgement, 400 on an unreadable amount
     */
    @POST
    @Path("/cashchanger/deposit")
    @Consumes(MediaType.TEXT_PLAIN)
    public synchronized Response startCashChangerDeposit(String dueCents) {
        try {
            cashChangerDue = Long.parseLong(dueCents.trim());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        cashChangerState = "RUNNING";
        cashChangerInserted = 0;
        cashChangerChange = 0;
        cashChangerCancelled = false;
        return Response.ok().build();
    }

    /**
     * Returns where the simulated deposit stands, in the bridge's status block.
     *
     * @return the status block
     */
    @GET
    @Path("/cashchanger/deposit")
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized Response getCashChangerDeposit() {
        return Response.ok("state=" + cashChangerState + "\ndue=" + cashChangerDue
                + "\ninserted=" + cashChangerInserted + "\nchange=" + cashChangerChange
                + "\ncancelled=" + cashChangerCancelled + "\nfailure=").build();
    }

    /**
     * Simulates the customer feeding a coin or a note (LC-07-15-02). The
     * machine closes the deposit by itself once the amount due is reached, and
     * gives back the difference.
     *
     * @param cents the value inserted, in cents
     * @return 200, 409 when no deposit runs, 400 on an unreadable value
     */
    @POST
    @Path("/cashchanger/insert")
    @Consumes(MediaType.TEXT_PLAIN)
    public synchronized Response insertIntoCashChanger(String cents) {
        if (!"RUNNING".equals(cashChangerState)) {
            return Response.status(Response.Status.CONFLICT).entity("Aucun depot en cours").build();
        }
        try {
            cashChangerInserted += Long.parseLong(cents.trim());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (cashChangerInserted >= cashChangerDue) {
            cashChangerChange = cashChangerInserted - cashChangerDue;
            cashChangerState = "DONE";
        }
        return Response.ok().build();
    }

    /**
     * Simulates closing the deposit on what was inserted, without change.
     *
     * @return a 200 acknowledgement
     */
    @POST
    @Path("/cashchanger/deposit/fix")
    public synchronized Response fixCashChangerDeposit() {
        if ("RUNNING".equals(cashChangerState)) {
            cashChangerState = "DONE";
        }
        return Response.ok().build();
    }

    /**
     * Simulates cancelling the deposit: everything inserted is given back.
     *
     * @return a 200 acknowledgement
     */
    @POST
    @Path("/cashchanger/deposit/cancel")
    public synchronized Response cancelCashChangerDeposit() {
        if ("RUNNING".equals(cashChangerState)) {
            cashChangerChange = cashChangerInserted;
            cashChangerCancelled = true;
            cashChangerState = "DONE";
        }
        return Response.ok().build();
    }

    /**
     * Returns the simulated levels block (LC-13-01-01).
     *
     * @return the levels block
     */
    @GET
    @Path("/cashchanger/levels")
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized Response getCashChangerLevels() {
        return Response.ok(cashChangerLevels).build();
    }

    /**
     * Sets the simulated levels block, as the machine's own back office would
     * flag its denominations (LC-13-01-01).
     *
     * @param levels the levels block, {@code key=value} per line
     * @return a 200 acknowledgement
     */
    @POST
    @Path("/cashchanger/levels")
    @Consumes(MediaType.TEXT_PLAIN)
    public synchronized Response setCashChangerLevels(String levels) {
        cashChangerLevels = levels == null ? "" : levels;
        return Response.ok().build();
    }

    /**
     * The simulated cash level (BO-11-03-03): how many of each denomination,
     * in cents, the machine holds, highest first.
     */
    private final java.util.TreeMap<Long, Long> cashChangerStock = new java.util.TreeMap<>(
            java.util.Map.of(5000L, 4L, 2000L, 10L, 1000L, 20L, 500L, 20L, 200L, 50L, 100L, 50L,
                    50L, 40L, 20L, 40L, 10L, 40L));

    /**
     * Simulates the machine dispensing a withdrawal (BO-11-03-03): it hands
     * out, highest denomination first, as much of the amount as its stock
     * allows, and reports what it dispensed.
     *
     * @param cents the amount asked for, in cents
     * @return the outcome block, 400 on an unreadable amount
     */
    @POST
    @Path("/cashchanger/dispense")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized Response dispenseFromCashChanger(String cents) {
        long asked;
        try {
            asked = Long.parseLong(cents.trim());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        long remaining = asked;
        for (Long denomination : cashChangerStock.descendingKeySet()) {
            long taken = Math.min(remaining / denomination, cashChangerStock.get(denomination));
            cashChangerStock.put(denomination, cashChangerStock.get(denomination) - taken);
            remaining -= taken * denomination;
        }
        return Response.ok("dispensed=" + (asked - remaining)).build();
    }

    /**
     * Simulates a loan fed into the machine (BO-11-03-03): the amount is taken
     * in, broken into denominations highest first, and reported as accepted.
     *
     * @param cents the amount brought, in cents
     * @return the outcome block, 400 on an unreadable amount
     */
    @POST
    @Path("/cashchanger/replenish")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized Response replenishCashChanger(String cents) {
        long brought;
        try {
            brought = Long.parseLong(cents.trim());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        long remaining = brought;
        for (Long denomination : cashChangerStock.descendingKeySet()) {
            cashChangerStock.merge(denomination, remaining / denomination, Long::sum);
            remaining %= denomination;
        }
        return Response.ok("accepted=" + (brought - remaining)).build();
    }

    /**
     * Returns the simulated cash level (BO-11-03-03): the total, then each
     * denomination and its count.
     *
     * @return the inventory block
     */
    @GET
    @Path("/cashchanger/inventory")
    @Produces(MediaType.TEXT_PLAIN)
    public synchronized Response getCashChangerInventory() {
        StringBuilder block = new StringBuilder();
        long total = 0L;
        for (java.util.Map.Entry<Long, Long> entry : cashChangerStock.entrySet()) {
            block.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            total += entry.getKey() * entry.getValue();
        }
        return Response.ok("total=" + total + "\n" + block).build();
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
     * Imprime une feuille sur la station facturette simulée (BO-03-04-14) :
     * la feuille s'ajoute au buffer de la station, distinct de celui du
     * rouleau.
     *
     * @param content la feuille, en texte
     * @return 200 toujours, la station simulée ne manquant jamais de feuille
     */
    @POST
    @Path("/slip/print")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response printSlip(String content) {
        slipBuffer.updateAndGet(current -> current + content + "\n");
        return Response.ok().build();
    }

    /**
     * Retourne les feuilles imprimées sur la station facturette simulée
     * (BO-03-04-14).
     *
     * @return le contenu de la station
     */
    @GET
    @Path("/slip/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getSlipContent() {
        return Response.ok(slipBuffer.get()).build();
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
     * Relays the terminal's ACCEPT decision to the register, with the receipt
     * the terminal returns when the body carries one (LC-08-05-07).
     *
     * @param receipt the terminal receipt, customer then merchant copy after
     *        {@code --/--}; empty for none
     * @return 200, or 409 when no card request is pending
     */
    @POST
    @Path("/tpe/accept")
    public Response tpeAccept(String receipt) {
        if (!virtualTerminal().accept(receipt)) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
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
        if (!virtualTerminal().refuse()) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        return Response.ok().build();
    }

    // --- TPE PAR LE PONT MATERIEL (BO-04-01-48) ---

    /** The status block of the last payment the bridge was asked for, or null when none ran. */
    private final AtomicReference<String> paymentStatus = new AtomicReference<>(null);

    /**
     * How many readings still find the last payment under way: a cardholder
     * takes the time of one reading, as at a real terminal, so the register's
     * own screen settles before the decision lands.
     */
    private final java.util.concurrent.atomic.AtomicInteger runningReadings =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How many readings each new bridge payment answers under way before its
     * decision: one by default, the realistic cardholder; {@code 0} makes the
     * simulated terminal answer DONE at the very first reading, the instant
     * decision a real terminal can give, to replay the race between the
     * payment follower and the payment screen in dev.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.mock.tpe.running-readings", defaultValue = "1")
    int paymentRunningReadings = 1;

    /**
     * Simulates a card debit asked through the hardware bridge: the simulated
     * terminal accepts at once on the primary monetique server, as the
     * certified client of a real till answers (BO-04-01-48).
     *
     * @param cents the amount to debit, in cents
     * @return 200, or 400 for an unreadable amount
     */
    @POST
    @Path("/payment")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startPayment(String cents) {
        return answerPayment(cents, com.intermarche.pos.ui.hardware.terminal.VerifoneTags.PRIMARY_SERVER);
    }

    /**
     * Simulates a card credit (refund) asked through the hardware bridge, on
     * the primary monetique server (BO-04-01-48).
     *
     * @param cents the amount to credit, in cents
     * @return 200, or 400 for an unreadable amount
     */
    @POST
    @Path("/payment/credit")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startCredit(String cents) {
        return answerPayment(cents, com.intermarche.pos.ui.hardware.terminal.VerifoneTags.PRIMARY_SERVER);
    }

    /**
     * Simulates the forced degraded debit (LC-07-08-02): the terminal was asked
     * to force the secondary server and answers the {@code VWAY} value it was
     * asked to force (BO-04-01-48).
     *
     * @param cents the amount to debit, in cents
     * @return 200, or 400 for an unreadable amount
     */
    @POST
    @Path("/payment/degraded")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startDegradedPayment(String cents) {
        return answerPayment(cents, com.intermarche.pos.ui.hardware.terminal.VerifoneTags.DEGRADED_SERVER);
    }

    /**
     * Simulates the forced degraded credit (LC-07-08-02), answered on the
     * secondary server it was asked to force (BO-04-01-48).
     *
     * @param cents the amount to credit, in cents
     * @return 200, or 400 for an unreadable amount
     */
    @POST
    @Path("/payment/credit/degraded")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startDegradedCredit(String cents) {
        return answerPayment(cents, com.intermarche.pos.ui.hardware.terminal.VerifoneTags.DEGRADED_SERVER);
    }

    /**
     * Simulates the combined card and meal-voucher debit (LC-07-06-02): the
     * total, the eligible amount and, when the degraded mode forced it, the
     * server, separated by semicolons; the terminal answers on the forced
     * server, the primary one otherwise (BO-04-01-48).
     *
     * @param amounts {@code total;eligible} cents, then {@code ;4} when forced
     * @return 200, or 400 for an unreadable amount
     */
    @POST
    @Path("/payment/combined")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response startCombinedPayment(String amounts) {
        String[] parts = (amounts == null ? "" : amounts).split(";");
        String server = parts.length > 2 ? parts[2].trim()
                : com.intermarche.pos.ui.hardware.terminal.VerifoneTags.PRIMARY_SERVER;
        return answerPayment(parts[0], server);
    }

    /**
     * Reads where the simulated bridge payment stands: under way at the first
     * reading after the request, the cardholder being asked for the card, then
     * its final block; {@code state=IDLE} before any payment.
     *
     * @return the status block
     */
    @GET
    @Path("/payment")
    @Produces(MediaType.TEXT_PLAIN)
    public String paymentStatus() {
        String status = paymentStatus.get();
        if (status == null) {
            return "state=IDLE\n";
        }
        return runningReadings.getAndDecrement() > 0 ? "state=RUNNING\nline1=INSERER CARTE\nline2=\n" : status;
    }

    /**
     * Parks the simulated terminal's answer to a bridge payment: accepted at
     * once, the WCL answer carrying the settled amount {@code D10}, the server
     * {@code VWAY} and an authorization number {@code D1C}, as the real
     * terminal returns them (BO-04-01-48).
     *
     * @param cents the amount, in cents
     * @param server the {@code VWAY} server code the terminal answers
     * @return 200, or 400 for an unreadable amount
     */
    private Response answerPayment(String cents, String server) {
        String amount = cents == null ? "" : cents.trim();
        if (!amount.matches("[0-9]+")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Montant illisible").build();
        }
        String answer = "{RS}00{D10}" + amount + "{VWAY}" + server
                + "{D1C}" + String.format("%06d", random.nextInt(1_000_000));
        paymentStatus.set("state=DONE\nline1=\nline2=\nresult=00\napproved=true\nfailure=\nanswer64="
                + java.util.Base64.getEncoder().encodeToString(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        runningReadings.set(paymentRunningReadings);
        return Response.ok().build();
    }

    /**
     * What the register CURRENTLY knows how to sell, for the simulator's
     * article lists.
     * <p>
     * The simulator used to carry those lists written by hand, and they were
     * true of exactly one catalogue: the referential seed. A register is fed
     * by its store node, and the node's own feed carries another numbering
     * plan entirely — so every list went stale the day the demonstration
     * started going through the node, and each scan came back
     * "CODE INCONNU" without the operator being able to see why. A hand-kept
     * mirror of a table that another machine replaces cannot be kept right;
     * the only list that is never wrong is the one read off the register at
     * the moment of the scan.
     * <p>
     * The filter is the one the scan itself applies — {@code active = true} —
     * so what this answers is exactly what a scan will accept. An article
     * forbidden to sale IS listed: refusing it is one of the cases worth
     * demonstrating, and the flag is carried so the screen can say so.
     *
     * @param limit how many articles at most (bounded to 500)
     * @return the sellable articles as JSON, EAN, label, PLU and the
     *         forbidden flag, in catalogue order
     */
    @GET
    @Path("/catalog")
    @Produces(MediaType.APPLICATION_JSON)
    public Response catalog(@QueryParam("limit") Integer limit) {
        int bounded = (limit == null || limit <= 0 || limit > 500) ? 200 : limit;
        StringBuilder body = new StringBuilder("[");
        boolean first = true;
        for (com.intermarche.pos.domain.catalog.Product product
                : com.intermarche.pos.domain.catalog.Product
                        .<com.intermarche.pos.domain.catalog.Product>find(
                                "active = true order by ean").page(0, bounded).list()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append("{\"ean\":\"").append(escape(product.ean))
                    .append("\",\"label\":\"").append(escape(product.name))
                    .append("\",\"plu\":").append(product.plu == null || product.plu.isBlank()
                            ? "null" : "\"" + escape(product.plu) + "\"")
                    .append(",\"forbidden\":").append(product.forbiddenToSale)
                    .append('}');
        }
        return Response.ok(body.append(']').toString()).build();
    }

    /**
     * Escapes a value for the catalogue's JSON body.
     * <p>
     * An article label comes from a published file and is not trusted to be
     * JSON-safe: a quote in "Huile d'Olive" is harmless, a double quote or a
     * backslash would break the body the simulator parses.
     *
     * @param value the value, possibly null
     * @return the escaped value, never null
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    /** The driver configuration the register last handed over (BO-03-09-11 to -29). */
    private final java.util.concurrent.atomic.AtomicReference<String> configuration =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /** What the second printer printed (BO-03-09-22). */
    private final java.util.concurrent.atomic.AtomicReference<String> secondPrinterBuffer =
            new java.util.concurrent.atomic.AtomicReference<>("");

    /**
     * Receives the driver configuration of the register, as a real module would
     * before applying it (BO-03-09-11 to -29).
     *
     * @param block one {@code key=value} per line
     * @return 200
     */
    @POST
    @Path("/configuration")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response configure(String block) {
        configuration.set(block == null ? "" : block);
        return Response.ok().build();
    }

    /**
     * Shows the driver configuration last received, for the demonstration.
     *
     * @return the block
     */
    @GET
    @Path("/configuration")
    @Produces(MediaType.TEXT_PLAIN)
    public Response configuration() {
        return Response.ok(configuration.get()).build();
    }

    /**
     * Simulates the second printer plugged on the register (BO-03-09-22).
     *
     * @param queue the local print queue named by the register
     * @param page the page printed
     * @return 200
     */
    @POST
    @Path("/printer2/print")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response printOnSecondPrinter(@QueryParam("queue") String queue, String page) {
        secondPrinterBuffer.updateAndGet(current -> current + "[" + queue + "]\n" + page + "\n");
        return Response.ok().build();
    }

    /**
     * Shows what the second printer printed.
     *
     * @return the printed pages
     */
    @GET
    @Path("/printer2/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response secondPrinterContent() {
        return Response.ok(secondPrinterBuffer.get()).build();
    }
}
