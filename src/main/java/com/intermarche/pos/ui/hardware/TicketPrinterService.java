package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;

/**
 * Renders and prints receipts from the persisted entities.
 * <p>
 * Phase 1: the receipt carries the per-rate VAT ventilation (shared
 * {@link VatBreakdown} rule, so the printed amounts always match the persisted
 * totals), and every print beyond the first is marked as a numbered DUPLICATA
 * and recorded in the technical event journal.
 * <p>
 * Package placement: every consumer is a screen flow (sale, payment,
 * session, reprint, refund) — per the register's placement rule an
 * exclusively-IHM service leaves the service root; being cross-screen it
 * lives at the ui root, like PosState. The move also fixes a layering
 * inversion: it injects the ui hardware facade, which a service-root class
 * should never have done.
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
 * <p>
 * Placement: ui.hardware — every consumer is a ui.* screen service and the class drives the printer boundary; pos.service keeps only services serving other services.
 */
@ApplicationScoped
public class TicketPrinterService {

    /** The back-office parameters (EAN on paper, LC-02-04-04). */
    @jakarta.inject.Inject
    PosSettingsService posSettingsService;

    @Inject
    HardwareService hardwareService;

    @Inject
    TechnicalEventService technicalEventService;

    /** The live register state — carries the loyalty projection at print time. */
    @Inject
    com.intermarche.pos.ui.PosState posState;

    /**
     * The ticket the register is currently on (draft or just closed) — the
     * only one whose live loyalty projection is meaningful.
     *
     * @return that ticket's database id, or null
     */
    private Long currentOrLastTicketId() {
        // No null check on posState: the only call site is guarded by
        // `posState != null` a few lines above, so this method is never
        // reached without a state — a guard here could not fire.
        return posState.payment.ticketDbId != null
                ? posState.payment.ticketDbId : posState.lastClosedTicketId;
    }

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
        // BO-03-08-03: an administered header message, printed under the store
        // address; absent or blank, nothing is printed.
        String headerMessage = posSettingsService.ticketHeaderMessage();
        if (headerMessage != null && !headerMessage.isBlank()) {
            sb.append(center(headerMessage, WIDTH)).append("\n");
        }
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
            // A cancelled article (lot C4, BO-04-01-16) is kept in the ticket
            // only as a journal witness; it never prints on the receipt.
            if (line.cancelled) continue;
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // Quantity and unit price
            String qtyStr = DF.format(line.quantity);
            String unitPriceStr = DF.format(line.unitPrice);
            sb.append(String.format("%-20s %5s x %6s%n", label, qtyStr, unitPriceStr));
            if (posSettingsService.showEan() && line.ean != null && !line.ean.isEmpty()) {
                // LC-02-04-04: the EAN printed under the label when configured.
                sb.append("  ").append(line.ean).append("\n");
            }
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
        if (ticket.globalDiscountApplied != null) {
            sb.append(formatLine("REMISE TICKET",
                    "-" + DF.format(ticket.globalDiscountApplied) + " E"));
        }
        sb.append(formatLine("TOTAL TTC", DF.format(ticket.totalIncludingTax) + " E"));
        // Loyalty section (imfid lot 3): the DISPLAYED projection and its rule
        // labels — the only rule data that travels to the POS (spec §3),
        // indicative by doctrine (the ingestion's recomputation prevails).
        // It is read from the LIVE state, which still holds the projection at
        // print time; a later DUPLICATA therefore carries no section (the earn
        // is not persisted — known and accepted gap).
        // The state is checked for null: this printer is also built BY HAND in
        // unit tests (no CDI), where only the hardware collaborator is wired —
        // an absent register state simply means "no loyalty section".
        // BO-10-03-15: the advantage section is printed only when the back
        // office activated the fidelity advantages; disabled, no section.
        if (posSettingsService.fidelityAdvantagesEnabled()
                && posState != null && posState.fidelity.earnTotal != null
                && posState.fidelity.earnTotal.signum() > 0
                && ticketId.equals(currentOrLastTicketId())) {
            sb.append(formatLine("CAGNOTTE DU JOUR",
                    "+" + DF.format(posState.fidelity.earnTotal) + " E"));
            for (com.intermarche.pos.ui.fidelity.FidelityState.EarnLine earnLine
                    : posState.fidelity.earnEntries) {
                sb.append(formatLine("  " + earnLine.label, "+" + DF.format(earnLine.amount) + " E"));
            }
        }
        sb.append(formatLine("Dont TVA", DF.format(ticket.totalVat) + " E"));
        // Per-rate VAT ventilation (same rule as the persisted totals)
        VatBreakdown breakdown = new VatBreakdown();
        for (TicketLine line : ticket.lines) {
            // Cancelled articles (lot C4) are outside the sale: excluded from
            // the per-rate VAT ventilation exactly as from the totals.
            if (line.cancelled) continue;
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
        // BO-03-08-03 / BO-03-08-05: an administered footer message, printed
        // after the courtesy line; absent or blank, nothing is printed.
        String footerMessage = posSettingsService.ticketFooterMessage();
        if (footerMessage != null && !footerMessage.isBlank()) {
            sb.append(center(footerMessage, WIDTH)).append("\n");
        }

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
        if (state.ticket.globalDiscountApplied != null) {
            sb.append(formatLine("REMISE TICKET",
                    "-" + DF.format(state.ticket.globalDiscountApplied) + " E"));
        }
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
     * registry number (297 + 12 digits): a pure identifier — the registry
     * stays authoritative for the live balance, so any amount is printable
     * and the note is scannable as a payment on a future ticket.
     *
     * @param refund the persisted refund the credit note stems from
     * @param number the registry number of the issued credit note
     */
    public void printRefundVoucher(Refund refund, String number) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center("AVOIR", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("MONTANT", DF.format(refund.totalAmount) + " E"));
        sb.append(String.format("Emis le : %s%n",
                refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("\n").append(center("N° " + number, WIDTH)).append("\n");
        sb.append(center("(scannable en caisse - solde au registre)", WIDTH)).append("\n");
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the customer's proof of a voluntary loyalty refund (imfid spec
     * §28): the amount credited to the card balance at ingestion — the
     * printed slip is the in-hand evidence while the outbox travels.
     *
     * @param amount the amount refunded to the loyalty balance
     */
    public void printLoyaltyCredit(java.math.BigDecimal amount) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center("REMBOURSEMENT EN CAGNOTTE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("CREDIT CARTE", "+" + DF.format(amount) + " E"));
        sb.append(center("(visible sur la carte sous quelques minutes)", WIDTH)).append("\n");
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints a freshly issued gift-card voucher: the scannable registry
     * number and the loaded amount — the customer's proof, printed right
     * after the fiscal moment of the issuing sale (phase: credit notes &
     * gift cards).
     *
     * @param number the registry number of the card
     * @param amount the loaded amount
     */
    public void printGiftCardVoucher(String number, java.math.BigDecimal amount) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append(center("CARTE CADEAU", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("MONTANT CHARGE", DF.format(amount) + " E"));
        sb.append("\n").append(center("N° " + number, WIDTH)).append("\n");
        sb.append(center("(scannable en caisse - solde au registre)", WIDTH)).append("\n");
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
    /**
     * Prints an operator's badge ticket (LC-01-06-01): the badge id in
     * clear, usable by manual entry or by the register's own scan bus (a
     * graphical barcode would need printer barcode primitives, out of this
     * text printer's reach — the number IS the credential).
     *
     * @param employee the operator whose badge is reprinted
     */
    public void printOperatorBadge(com.intermarche.pos.domain.Employee employee) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("BADGE OPERATEUR", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("OPERATEUR", employee.loginName));
        sb.append(formatLine("BADGE", employee.badgeId != null ? employee.badgeId : "-"));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("SCANNEZ OU SAISISSEZ CE NUMERO", WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
    }
    /**
     * Prints the parked-ticket receipt (LC-04-01-02): the ticket number in
     * clear — the resume code —, the articles and the running total. The
     * number is what the resume path recognizes, scanned on the bus or
     * typed; this text printer has no barcode primitives, the number IS the
     * code.
     *
     * @param draft the freshly parked draft
     */
    public void printParkedTicket(com.intermarche.pos.domain.ticket.Ticket draft) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("TICKET EN ATTENTE", WIDTH)).append("\n");
        sb.append(center(draft.ticketNumber, WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        for (TicketLine line : draft.lines) {
            // A cancelled article (lot C4) never prints on the parked receipt.
            if (line.cancelled) continue;
            String label = line.productLabel.length() > 20
                    ? line.productLabel.substring(0, 20) : line.productLabel;
            sb.append(formatLine(label + " x" + DF.format(line.quantity),
                    DF.format(line.totalPrice) + " E"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("TOTAL EN ATTENTE", DF.format(draft.totalIncludingTax) + " E"));
        sb.append(center("SCANNEZ CE NUMERO POUR REPRENDRE", WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
    }
}