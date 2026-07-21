package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@ApplicationScoped
public class TicketPrinterService {

    @Inject
    HardwareService hardwareService;

    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));
    private static final int WIDTH = 42; // Largeur standard ticket 80mm

    /**
     * Charge le ticket depuis la BDD et l'imprime.
     */
    @Transactional
    public void printTicket(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket introuvable pour impression : " + ticketId);
        }
        StringBuilder sb = new StringBuilder();
        // En-tête
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center(ticket.store.name, WIDTH)).append("\n");
        sb.append(center(ticket.store.address.city, WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        // Infos Ticket
        sb.append(String.format("Ticket: %s%n", ticket.ticketNumber));
        sb.append(String.format("Date   : %s%n", ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(String.format("Vendeur: %s%n", ticket.cashier.getFullName()));
        sb.append("-".repeat(WIDTH)).append("\n");
        // Lignes
        for (TicketLine line : ticket.lines) {
            // Nom du produit (peut être tronqué)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // Quantité et Prix Unitaire
            String qtyStr = DF.format(line.quantity);
            String unitPriceStr = DF.format(line.unitPrice);
            sb.append(String.format("%-20s %5s x %6s%n", label, qtyStr, unitPriceStr));
            // Total ligne (aligné à droite)
            String lineTotal = DF.format(line.totalPrice);
            sb.append(String.format("%" + WIDTH + "s%n", lineTotal));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Totaux
        sb.append(formatLine("TOTAL TTC", DF.format(ticket.totalIncludingTax) + " E"));
        sb.append(formatLine("Dont TVA", DF.format(ticket.totalVat) + " E"));
        sb.append("\n");
        // Paiements
        sb.append(center("REGLEMENT", WIDTH)).append("\n");
        for (TicketPayment payment : ticket.payments) {
            String method = payment.getClass().getSimpleName().replace("Payment", "").toUpperCase();
            sb.append(formatLine(method, DF.format(payment.amount) + " E"));
        }
        // Pied de ticket
        sb.append("\n");
        sb.append(center("MERCI DE VOTRE VISITE", WIDTH)).append("\n");
        sb.append(center("A BIENTOT", WIDTH)).append("\n");
        // Envoi à l'imprimante
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    // --------------------------------------------------
    // NOUVEAU : Impression du Retour
    // --------------------------------------------------

    /**
     * Charge le remboursement depuis la BDD et l'imprime.
     */
    @Transactional
    public void printRefund(Long refundId) {
        Refund refund = Refund.findById(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("Remboursement introuvable pour impression : " + refundId);
        }
        Ticket original = Ticket.findById(refund.originalTicketId);
        StringBuilder sb = new StringBuilder();
        // En-tête
        if (original != null && original.store != null) {
            sb.append(center("INTERMARCHE", WIDTH)).append("\n");
            sb.append(center(original.store.name, WIDTH)).append("\n");
            if (original.store.address != null) {
                sb.append(center(original.store.address.city, WIDTH)).append("\n");
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Titre Retour
        sb.append(center("TICKET DE RETOUR", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        // Infos Retour
        sb.append(String.format("Date   : %s%n", refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (original != null) {
            sb.append(String.format("Ticket Original: %s%n", original.ticketNumber));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Lignes
        for (RefundLine line : refund.lines) {
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            String qtyStr = DF.format(line.quantity);
            String priceStr = DF.format(line.price);
            sb.append(String.format("%-20s %5s x %6s%n", label, qtyStr, priceStr));
            BigDecimal lineTotal = line.price.multiply(line.quantity);
            sb.append(String.format("%" + WIDTH + "s%n", DF.format(lineTotal)));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Total
        sb.append(formatLine("TOTAL REMBOURSE", DF.format(refund.totalAmount) + " E")).append("\n");
        // Pied
        sb.append("\n");
        sb.append(center("MERCI DE VOTRE VISITE", WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    // Helpers de formatage
    private String center(String text, int width) {
        if (text.length() >= width) return text;
        int pad = (width - text.length()) / 2;
        return " ".repeat(pad) + text;
    }

    private String formatLine(String left, String right) {
        int space = WIDTH - left.length() - right.length();
        if (space < 1) space = 1;
        return left + " ".repeat(space) + right + "\n";
    }
}