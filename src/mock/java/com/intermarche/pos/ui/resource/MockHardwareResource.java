package com.intermarche.pos.ui.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Path("/api/hardware")
@ApplicationScoped
public class MockHardwareResource {

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
}