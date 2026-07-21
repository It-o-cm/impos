package com.intermarche.pos.ui.hardware;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HardwareService {

    private static final Logger LOGGER = Logger.getLogger(HardwareService.class);

    @Inject
    @RestClient
    HardwareClient hardwareClient;

    /**
     * Demande une pesée au matériel.
     * @return le poids en kg, ou 0.0 si erreur.
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
     * Affiche un message personnalisé sur l'écran client.
     */
    public void displayMessage(String message) {
        try {
            hardwareClient.setDisplay(message);
        } catch (Exception e) {
            LOGGER.error("Erreur d'affichage", e);
        }
    }

    /**
     * Envoie l'ordre d'ouverture du tiroir caisse.
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
     * Interroge le matériel pour savoir si le tiroir est ouvert.
     * Utilisé par le filtre de sécurité.
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
     * Envoie le texte formaté à l'imprimante.
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
     * Coupe le papier.
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