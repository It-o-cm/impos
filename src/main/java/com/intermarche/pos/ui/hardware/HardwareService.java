package com.intermarche.pos.ui.hardware;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Facade over the hardware REST client, and the place where the register's
 * DEGRADED-MODE PHILOSOPHY lives: every peripheral call swallows its
 * exceptions — a dead scale, display, drawer or printer is logged and never
 * blocks a sale. The consequences are deliberate and worth knowing:
 * {@link #requestWeighing()} answers 0.0 on failure (callers treat it as
 * "no weight"), and {@link #isDrawerOpen()} answers FALSE on failure so a
 * broken drawer sensor cannot trap the register behind the drawer guard —
 * availability wins over the guard when the sensor itself is the failure.
 */
@ApplicationScoped
public class HardwareService {

    private static final Logger LOGGER = Logger.getLogger(HardwareService.class);

    @Inject
    @RestClient
    HardwareClient hardwareClient;

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
     * Shows a message on the customer line display.
     *
     * @param message the message to display
     */
    public void displayMessage(String message) {
        try {
            hardwareClient.setDisplay(message);
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
