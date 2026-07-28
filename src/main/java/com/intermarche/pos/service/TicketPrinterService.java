package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders and prints receipts from the persisted entities.
 * <p>
 * Phase 1: the receipt carries the per-rate VAT ventilation (shared
 * {@link VatBreakdown} rule, so the printed amounts always match the persisted
 * totals), and every print beyond the first is marked as a numbered DUPLICATA
 * and recorded in the technical event journal.
 * <p>
 * Format contract: 42-column monospace text (WIDTH), French decimal comma
 * (DF), rendered from PERSISTED entities — never from the in-memory state,
 * so a reprint is faithful by construction. The single exception is the
 * phase 6 training receipt, built from memory precisely because training
 * persists nothing, and framed as NON VALABLE. Five documents share the
 * helpers: sale ticket (with digital-receipt footer link), X/Z session
 * report, refund ticket (with restituted VAT ventilation), refund store
 * voucher (scannable STORE_VOUCHER format when encodable, plain otherwise)
 * and the training receipt.
 */
@ApplicationScoped
public class TicketPrinterService {

    @Inject
    HardwareService hardwareService;

    @Inject
    TechnicalEventService technicalEventService;

    /** French display format for amounts on the receipt. */
    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** Standard 80mm receipt width, in characters. */
    private static final int WIDTH = 42;

    /**
     * Loads a ticket from the database and prints it. The first print is the
     * original; any further print is marked "DUPLICATA N°x", counted on the
     * ticket and recorded in the journal.
     *
     * @param ticketId the database id of the ticket to print
     */
    @Transactional
    public void printTicket(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket introuvable pour impression : " + ticketId);
        }

        boolean duplicata = ticket.printCount >= 1;
        int duplicataNumber = ticket.printCount; // 1st reprint = duplicata n°1

        StringBuilder sb = new StringBuilder();
        // Header
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center(ticket.store.name, WIDTH)).append("\n");
        sb.append(center(ticket.store.address.city, WIDTH)).append("\n");
        if (duplicata) {
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(center(String.format("*** DUPLICATA N°%d ***", duplicataNumber), WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Ticket info
        sb.append(String.format("Ticket: %s%n", ticket.ticketNumber));
        sb.append(String.format("Date   : %s%n", ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(String.format("Vendeur: %s%n", ticket.cashier.getFullName()));
        sb.append("-".repeat(WIDTH)).append("\n");
        // Lines
        // Phase 7: per-line valuation traces (engine advantages), printed as
        // deltas under their article — loaded once, keyed by lineUid
        java.util.Map<String, TicketLineValuation> valuations = new java.util.HashMap<>();
        for (TicketLineValuation v : TicketLineValuation.<TicketLineValuation>list("ticket.id", ticket.id)) {
            valuations.put(v.lineUid, v);
        }
        for (TicketLine line : ticket.lines) {
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // Quantity and unit price
            String qtyStr = DF.format(line.quantity);
            String unitPriceStr = DF.format(line.unitPrice);
            sb.append(String.format("%-20s %5s x %6s%n", label, qtyStr, unitPriceStr));
            // Line total (right-aligned)
            String lineTotal = DF.format(line.totalPrice);
            sb.append(String.format("%" + WIDTH + "s%n", lineTotal));
            // Engine advantage on this line: printed as a signed delta
            TicketLineValuation valuation = valuations.get(line.lineUid);
            if (valuation != null && valuation.valuedTotal.compareTo(valuation.localTotal) != 0) {
                java.math.BigDecimal delta = valuation.valuedTotal.subtract(valuation.localTotal);
                String advLabel = valuation.advantageLabel != null ? valuation.advantageLabel
                        : (valuation.offerLabel != null ? valuation.offerLabel : "AVANTAGE");
                if (advLabel.length() > 26) advLabel = advLabel.substring(0, 26);
                sb.append(formatLine("  " + advLabel, DF.format(delta) + " E"));
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Totals
        sb.append(formatLine("TOTAL TTC", DF.format(ticket.totalIncludingTax) + " E"));
        sb.append(formatLine("Dont TVA", DF.format(ticket.totalVat) + " E"));
        // Per-rate VAT ventilation (same rule as the persisted totals)
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            breakdown.add(line.vatRate, line.totalPrice);
        }
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            sb.append(formatLine("  TVA " + bucket.getRateFormatted(),
                    "HT " + DF.format(bucket.totalExcludingTax) + "  TVA " + DF.format(bucket.vatAmount)));
        }
        sb.append("\n");
        // Payments
        sb.append(center("REGLEMENT", WIDTH)).append("\n");
        for (TicketPayment payment : ticket.payments) {
            String method = payment.getClass().getSimpleName().replace("Payment", "").toUpperCase();
            sb.append(formatLine(method, DF.format(payment.amount) + " E"));
        }
        // Footer, with the online digital receipt link
        sb.append("\n");
        if (ticket.digitalKey != null) {
            sb.append(center("Votre ticket en ligne :", WIDTH)).append("\n");
            sb.append(center("/t/" + ticket.id + "/" + ticket.digitalKey, WIDTH)).append("\n");
            sb.append("\n");
        }
        sb.append(center("MERCI DE VOTRE VISITE", WIDTH)).append("\n");
        sb.append(center("A BIENTOT", WIDTH)).append("\n");

        // Count the print and journal the duplicata before sending to the printer
        ticket.printCount++;
        ticket.persist();
        if (duplicata) {
            technicalEventService.log(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                    ticket.ticketNumber + " n°" + duplicataNumber);
        }

        // Send to the printer
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    // --------------------------------------------------
    // Session reports (X / Z)
    // --------------------------------------------------

    /**
     * Prints a session report: X (read-only snapshot) or Z (closing report
     * with counted amount, variance and withdrawal when the report closes the
     * session).
     *
     * @param report the report content built by the session service
     */
    public void printSessionReport(CashSessionService.SessionReport report) {
        var session = report.session;
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center(report.closing ? "RAPPORT Z - CLOTURE" : "RAPPORT X - LECTURE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Session : %s%n", session.sessionNumber));
        sb.append(String.format("Caisse  : %s%n", session.terminalId));
        sb.append(String.format("Ouverte : %s%n", session.openingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (report.closing && session.closingDate != null) {
            sb.append(String.format("Fermée  : %s%n", session.closingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("Tickets clos", String.valueOf(report.ticketCount)));
        sb.append(formatLine("CA TTC", DF.format(report.totalIncludingTax) + " E"));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("REGLEMENTS", WIDTH)).append("\n");
        for (var entry : report.totalsByMethod.entrySet()) {
            sb.append(formatLine(entry.getKey(), DF.format(entry.getValue()) + " E"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report.totalRefunds.signum() > 0) {
            sb.append(formatLine("Remboursements", DF.format(report.totalRefunds) + " E"));
        }
        sb.append(formatLine("Fond de caisse", DF.format(session.openingFloat) + " E"));
        sb.append(formatLine("Especes theorique", DF.format(report.theoreticalCash) + " E"));
        if (report.closing) {
            sb.append(formatLine("Especes comptees", DF.format(session.countedAmount) + " E"));
            sb.append(formatLine("ECART", DF.format(session.variance) + " E"));
            sb.append(formatLine("Prelevement", DF.format(session.withdrawnAmount) + " E"));
        }
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    // --------------------------------------------------
    // Refund printing
    // --------------------------------------------------

    /**
     * Loads a refund from the database and prints it.
     *
     * @param refundId the database id of the refund to print
     */
    @Transactional
    public void printRefund(Long refundId) {
        Refund refund = Refund.findById(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("Remboursement introuvable pour impression : " + refundId);
        }
        Ticket original = Ticket.findById(refund.originalTicketId);
        StringBuilder sb = new StringBuilder();
        // Header
        if (original != null && original.store != null) {
            sb.append(center("INTERMARCHE", WIDTH)).append("\n");
            sb.append(center(original.store.name, WIDTH)).append("\n");
            if (original.store.address != null) {
                sb.append(center(original.store.address.city, WIDTH)).append("\n");
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Refund title
        sb.append(center("TICKET DE RETOUR", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        // Refund info
        sb.append(String.format("Date   : %s%n", refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (original != null) {
            sb.append(String.format("Ticket Original: %s%n", original.ticketNumber));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Lines
        for (RefundLine line : refund.lines) {
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            String qtyStr = DF.format(line.quantity);
            String priceStr = DF.format(line.price);
            sb.append(String.format("%-20s %5s x %6s%n", label, qtyStr, priceStr));
            BigDecimal lineTotal = line.price.multiply(line.quantity);
            sb.append(String.format("%" + WIDTH + "s%n", DF.format(lineTotal)));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Total, method and VAT restitution
        sb.append(formatLine("TOTAL REMBOURSE", DF.format(refund.totalAmount) + " E"));
        if (refund.refundMethod != null) {
            sb.append(formatLine("Mode", refundMethodLabel(refund.refundMethod)));
        }
        VatBreakdown refundBreakdown = new VatBreakdown();
        for (RefundLine line : refund.lines) {
            refundBreakdown.add(line.vatRate,
                    line.price.multiply(line.quantity).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        for (VatBreakdown.Bucket bucket : refundBreakdown.getBuckets()) {
            sb.append(formatLine("  TVA " + bucket.getRateFormatted(),
                    "HT " + DF.format(bucket.totalExcludingTax) + "  TVA " + DF.format(bucket.vatAmount)));
        }
        sb.append("\n");
        // Footer
        sb.append("\n");
        sb.append(center("MERCI DE VOTRE VISITE", WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the training receipt from the in-memory state: no persisted
     * ticket exists in training mode, so the receipt is built from the cart
     * and payment entries, framed by unambiguous FORMATION banners and
     * carrying no number, no signature and no VAT ventilation.
     *
     * @param state the current POS state
     */
    public void printTrainingReceipt(com.intermarche.pos.ui.PosState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("*".repeat(WIDTH), WIDTH)).append("\n");
        sb.append(center("MODE FORMATION", WIDTH)).append("\n");
        sb.append(center("TICKET NON VALABLE", WIDTH)).append("\n");
        sb.append(center("*".repeat(WIDTH), WIDTH)).append("\n\n");
        for (com.intermarche.pos.ui.ticket.TicketState.TicketItem item : state.ticket.items) {
            sb.append(formatLine(item.label, item.getPriceFormatted() + " E"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("TOTAL", state.ticket.getTotalFormatted() + " E"));
        for (com.intermarche.pos.ui.payment.PaymentState.PaymentEntry entry : state.payment.payments) {
            sb.append(formatLine("  " + entry.method, DF.format(entry.amount) + " E"));
        }
        sb.append("\n").append(center("*** FORMATION - SANS VALEUR ***", WIDTH)).append("\n\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the store voucher issued by a voucher refund. When the amount is
     * encodable (4 cent digits), the printed number matches the STORE_VOUCHER
     * pattern (50 + 8-digit serial + 4-digit cents) and is therefore
     * scannable as a payment voucher on a future ticket.
     *
     * @param refund the persisted refund the voucher stems from
     * @param encodable true when the amount fits the encoded voucher format
     */
    public void printRefundVoucher(Refund refund, boolean encodable) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center("BON D'ACHAT", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("MONTANT", DF.format(refund.totalAmount) + " E"));
        sb.append(String.format("Emis le : %s%n",
                refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (encodable) {
            long cents = refund.totalAmount.setScale(2, java.math.RoundingMode.HALF_UP)
                    .movePointRight(2).longValueExact();
            String number = String.format("50%08d%04d", refund.id, cents);
            sb.append("\n").append(center("N° " + number, WIDTH)).append("\n");
            sb.append(center("(scannable en caisse)", WIDTH)).append("\n");
        } else {
            sb.append("\n").append(center("A DEDUIRE EN CAISSE SUR PRESENTATION", WIDTH)).append("\n");
        }
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Returns the printed label of a refund method.
     *
     * @param method the refund method
     * @return the receipt label
     */
    private String refundMethodLabel(Refund.RefundMethod method) {
        return switch (method) {
            case CASH -> "ESPECES";
            case CARD -> "CARTE BANCAIRE";
            case VOUCHER -> "BON D'ACHAT";
            case LOYALTY -> "CAGNOTTE";
        };
    }

    // --------------------------------------------------
    // Formatting helpers
    // --------------------------------------------------

    /**
     * Centers a text within the given width by left-padding.
     *
     * @param text the text to center
     * @param width the receipt width in characters
     * @return the padded text
     */
    private String center(String text, int width) {
        if (text.length() >= width) return text;
        int pad = (width - text.length()) / 2;
        return " ".repeat(pad) + text;
    }

    /**
     * Formats a label / value pair on one receipt line, value right-aligned.
     *
     * @param left the left label
     * @param right the right value
     * @return the formatted line, newline included
     */
    private String formatLine(String left, String right) {
        int space = WIDTH - left.length() - right.length();
        if (space < 1) space = 1;
        return left + " ".repeat(space) + right + "\n";
    }
}
