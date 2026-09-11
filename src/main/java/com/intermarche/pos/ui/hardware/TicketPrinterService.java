package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.ticket.CardPayment;
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

        String content = renderTicket(ticket, duplicata, duplicataNumber);

        // Count the print and journal the duplicata before sending to the printer
        ticket.printCount++;
        ticket.persist();
        if (duplicata) {
            technicalEventService.log(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                    ticket.ticketNumber + " n°" + duplicataNumber);
        }

        // Send to the printer
        hardwareService.printReceipt(content);
        hardwareService.cutPaper();
    }

    /**
     * Renders a sale ticket as 42-column text, WITHOUT printing it and without
     * touching a single row.
     *
     * <p>Split out of {@link #printTicket(Long)} so the store node can render a
     * ticket a register asks it for (LC-08-05-05): a duplicata of a sale made on
     * ANOTHER register must look exactly like the original, and the only way to
     * guarantee that is to run the very same renderer over the very same entity
     * rather than a second rendering of a payload.
     *
     * @param ticket the ticket to render
     * @param duplicata whether the DUPLICATA banner is printed
     * @param duplicataNumber the duplicata's rank, meaningless when not a duplicata
     * @return the ticket as printable text
     */
    public String renderTicket(Ticket ticket, boolean duplicata, int duplicataNumber) {
        StringBuilder sb = new StringBuilder();
        // Header. The logo prints above the store name when this till has one; the
        // text line stays regardless, so a receipt is readable even on a printer that
        // ignores images or a deployment with no logo file.
        String logo = ReceiptLogo.directive();
        if (!logo.isEmpty()) {
            sb.append(logo).append("\n");
        }
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
        // LC-08-01-07: the store administers the order the articles print in, and
        // whether they are grouped under a family heading. The stored lines keep
        // their own order — this is a rendering, not the record.
        String lineOrder = TicketLineOrder.normalize(posSettingsService.ticketLineOrder());
        boolean groupByFamily = TicketLineOrder.groupsByFamily(lineOrder);
        String printedFamily = null;
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, lineOrder)) {
            // A cancelled article (lot C4, BO-04-01-16) is kept in the ticket
            // only as a journal witness; it never prints on the receipt.
            if (line.cancelled) continue;
            if (groupByFamily) {
                String family = TicketLineOrder.familyOf(line);
                if (!family.equals(printedFamily)) {
                    sb.append(center(family, WIDTH)).append("\n");
                    printedFamily = family;
                }
            }
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // Quantity and unit price
            String qtyStr = DF.format(line.quantity);
            String unitPriceStr = DF.format(line.unitPrice);
            // LC-02-03-02 and LC-02-13-18: quantity, unit price and line total on the
            // receipt as on the two screens. An article sold by a unit of measure
            // states that unit — "2,360 m x 4,99" — because the figure alone does not
            // say what was bought.
            String unit = line.unitName == null || line.unitName.isBlank()
                    ? "" : line.unitName.trim();
            sb.append(unit.isEmpty()
                    ? String.format("%-20s %5s x %6s%n", label, qtyStr, unitPriceStr)
                    : String.format("%-20s %5s %s x %6s%n", label, qtyStr, unit, unitPriceStr));
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
                && ticket.id.equals(currentOrLastTicketId())) {
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
        // BO-10-06-04: the per-rate VAT ventilation is printed only when the
        // back office asks for it; disabled, the ticket carries no breakdown.
        if (posSettingsService.vatBreakdownEnabled()) {
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                sb.append(formatLine("  TVA " + bucket.getRateFormatted(),
                        "HT " + DF.format(bucket.totalExcludingTax) + "  TVA " + DF.format(bucket.vatAmount)));
            }
        }
        sb.append("\n");
        // Payments
        sb.append(center("REGLEMENT", WIDTH)).append("\n");
        for (TicketPayment payment : ticket.payments) {
            String method = payment.getClass().getSimpleName().replace("Payment", "").toUpperCase();
            // LC-07-03-06: the rounding is stored with the sign the ledger needs
            // (so the settlements sum to the total) and PRINTED with the opposite
            // one, because on paper it reads as the adjustment to what the customer
            // hands over: "A PAYER 24,62 / ESPECES 24,60 / ARRONDI -0,02". This is
            // the single place that inversion happens.
            // LC-07-14-05: a foreign settlement states the three figures the
            // customer and the accounts both need — what was handed over, what it
            // was worth, and the rate that connects them.
            if (payment instanceof com.intermarche.pos.domain.ticket.ForeignCurrencyPayment devise) {
                sb.append(formatLine("DEVISE " + safe(devise.currencyCode),
                        DF.format(payment.amount) + " E"));
                sb.append("  ").append(DF.format(devise.foreignAmount)).append(" ")
                        .append(safe(devise.currencyCode)).append("  TAUX ")
                        .append(devise.exchangeRate == null ? "" : devise.exchangeRate.toPlainString())
                        .append("\n");
                continue;
            }
            if (payment instanceof com.intermarche.pos.domain.ticket.RoundingPayment) {
                sb.append(formatLine("ARRONDI", DF.format(payment.amount.negate()) + " E"));
                continue;
            }
            sb.append(formatLine(method, DF.format(payment.amount) + " E"));
            // LC-08-04-06 [sic LC-07-09-06]: a credit settlement names its debtor
            // under its own line — date, account number, account name. A month
            // later this receipt is what the account is reconciled against, and an
            // amount owed by nobody cannot be reconciled against anything.
            if (payment instanceof com.intermarche.pos.domain.ticket.CreditPayment credit) {
                String saleDay = ticket.creationDate == null ? ""
                        : ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                sb.append("  ").append(saleDay).append("  COMPTE ")
                        .append(credit.accountNumber == null ? "" : credit.accountNumber)
                        .append("\n");
                sb.append("  ").append(credit.accountName == null ? "" : credit.accountName)
                        .append("\n");
            }
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
        // The ticket number as a barcode, last thing on the receipt: it is what a
        // return, a duplicate or a claim is looked up by, and typing it back by hand
        // is where the mistakes happen. The hardware bridge turns the directive into
        // the printer's own barcode command; the POS stays text.
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        return sb.toString();
    }

    /**
     * Prints a ticket rendered ELSEWHERE, verbatim (LC-08-05-05).
     *
     * <p>The text comes from the store node, which holds the sales of every register
     * of the shop; this one owns neither that ticket nor its print counter, so it
     * counts nothing and journals nothing here — it is a printer, not the register of
     * record. The DUPLICATA banner is part of what it received.
     *
     * @param content the ticket as the store node rendered it
     */
    public void printRenderedTicket(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        hardwareService.printReceipt(content);
        hardwareService.cutPaper();
    }

    /**
     * Writes the directive asking the hardware bridge for a barcode.
     *
     * <p>The print contract is plain text and stays that way: a line of its own
     * carrying this directive is the whole extension, and the bridge is what knows
     * ESC/POS. A value the bridge cannot encode prints as the directive line itself,
     * which is visible on the receipt rather than silently missing.
     *
     * @param value the value to encode
     * @return the directive line
     */
    private static String barcode(String value) {
        return "[[BARCODE " + value + "]]";
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
        // BO-10-06-04: same VAT-breakdown gate on the refund ticket.
        if (posSettingsService.vatBreakdownEnabled()) {
            for (VatBreakdown.Bucket bucket : refundBreakdown.getBuckets()) {
                sb.append(formatLine("  TVA " + bucket.getRateFormatted(),
                        "HT " + DF.format(bucket.totalExcludingTax) + "  TVA " + DF.format(bucket.vatAmount)));
            }
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
    /**
     * Returns a printable string for a value that may be absent.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

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

    /**
     * Prints the ticket's identification barcode ALONE (LC-08-01-04): the number in
     * clear and the barcode the register scans it back with, and nothing else.
     *
     * <p>This is not a copy of the ticket. It is what lets a customer or a colleague
     * carry the sale's identity to another desk — a return, a claim, a document — on
     * a slip of paper instead of by reading a number out loud. So it does NOT count
     * as a print: the duplicata counter and its journal entry belong to the ticket
     * itself, and this slip states nothing about the sale.
     *
     * @param ticketId the database id of the ticket whose identity is printed
     */
    @Transactional
    public void printTicketIdentityBarcode(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center("IDENTIFIANT TICKET", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(ticket.ticketNumber, WIDTH)).append("\n");
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    // --------------------------------------------------
    // Card receipts (LC-08-03-04 / -10 / -11 / -12)
    // --------------------------------------------------

    /**
     * Prints the card receipt of a closed sale: one slip per card payment of
     * the ticket, carrying the monetique traces the register holds (amount,
     * authorization number, degraded acceptance). A ticket settled without a
     * card prints nothing — the caller does not have to know whether a card
     * was used.
     *
     * @param ticketId the database id of the closed or draft ticket
     * @param signatureRequired whether the customer must sign the slip
     *        (LC-08-03-11): a signature line is then printed
     * @param mention the mention qualifying the slip — {@code "DUPLICATA"} on a
     *        reprint (LC-08-05-09) — or null on the original
     * @return the number of slips printed — zero when the ticket is unknown or was
     *         settled without a card, which is not a failure but IS something the
     *         operator must be told when they asked for a duplicate
     */
    @Transactional
    public int printCardReceipt(Long ticketId, boolean signatureRequired, String mention) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return 0;
        }
        int printed = 0;
        for (TicketPayment payment : ticket.payments) {
            if (!(payment instanceof CardPayment card)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            cardHeader(sb, ticket.store != null ? ticket.store.name : null, mention);
            sb.append(String.format("Ticket : %s%n", ticket.ticketNumber));
            sb.append(String.format("Date   : %s%n",
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(formatLine("MONTANT", DF.format(card.amount) + " E"));
            if (card.authorizationNumber != null && !card.authorizationNumber.isBlank()) {
                sb.append(formatLine("AUTORISATION", card.authorizationNumber));
            }
            if (card.degradedMode) {
                sb.append(center("MODE DEGRADE", WIDTH)).append("\n");
            }
            if (signatureRequired) {
                sb.append("\n").append(center("SIGNATURE DU CLIENT", WIDTH)).append("\n\n\n");
            }
            sb.append("\n");
            hardwareService.printReceipt(sb.toString());
            hardwareService.cutPaper();
            printed++;
        }
        return printed;
    }

    /**
     * Prints the withdrawal ticket of one tender taken out of the drawer
     * (LC-12-03-07).
     *
     * <p>It states what left and in what shape. For CASH that means the denominations
     * — how many of each, for how much — because what a manager checks against the
     * bag is the count and not only the total; for any other tender it means the
     * transactions that brought it in, each with its number and its amount, because
     * that is what a bundle of cheques is checked against.
     *
     * @param methodLabel   the tender's wording, as administered
     * @param amount        the amount withdrawn
     * @param lines         the detail lines to print, already formatted as label and value
     * @param operator      the operator's name, blank when unknown
     * @param terminalId    the register the withdrawal was made on
     */
    public void printWithdrawalTicket(String methodLabel, BigDecimal amount,
            java.util.List<String[]> lines, String operator, String terminalId) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("PRELEVEMENT", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Moyen  : %s%n", methodLabel));
        if (terminalId != null) {
            sb.append(String.format("Caisse : %s%n", terminalId));
        }
        sb.append(String.format("Date   : %s%n", java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format("Operateur : %s%n", operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (lines != null) {
            for (String[] line : lines) {
                sb.append(formatLine(line[0], line[1]));
            }
            if (!lines.isEmpty()) {
                sb.append("-".repeat(WIDTH)).append("\n");
            }
        }
        sb.append(formatLine("TOTAL PRELEVE", DF.format(amount) + " E"));
        sb.append("\n");
        sb.append("Signature: ..............................\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the settlement-transfer ticket (LC-12-10-05).
     *
     * <p>Three facts and nothing else: where the amount was taken from, where it was
     * put, and how much. A transfer repairs a mis-keying and its paper exists so the
     * repair can be checked — it is not a receipt and states no sale.
     *
     * @param fromLabel  the source tender's wording
     * @param toLabel    the destination tender's wording
     * @param amount     the amount transferred
     * @param operator   the operator's name, blank when unknown
     * @param terminalId the register the transfer was made on
     */
    public void printTransferTicket(String fromLabel, String toLabel, BigDecimal amount,
            String operator, String terminalId) {
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("TRANSFERT REGLEMENT", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format("Caisse : %s%n", terminalId));
        }
        sb.append(String.format("Date   : %s%n", java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format("Operateur : %s%n", operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("DE", fromLabel));
        sb.append(formatLine("VERS", toLabel));
        sb.append(formatLine("MONTANT", DF.format(amount) + " E"));
        sb.append("\n");
        sb.append("Signature: ..............................\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the abandon ticket of a sale that was given up (LC-04-04-11/12).
     *
     * <p>IT IS PRINTED BEFORE THE DRAFT IS CANCELLED, and it has to be: what it states
     * is a sale that is about to stop existing, and a paper produced after the
     * cancellation would have nothing left to read. It is not a receipt — no total due,
     * no VAT table, no barcode — and it counts as no print of the ticket.
     *
     * <p>Its purpose is the counter-signature of a gesture that destroys a sale: the
     * reason the operator gave, the operator's own name, and — when the shop asks for
     * it — the articles that were in the basket, so a manager reading the drawer at
     * closing time can see what was given up and not merely how much.
     *
     * @param ticketId   the database id of the draft being abandoned
     * @param reason     the reason the operator gave, blank when the shop asks for none
     * @param withDetail whether the articles are listed
     * @param operator   the operator's name, blank when unknown
     * @return true when a ticket was printed
     */
    @Transactional
    public boolean printAbandonTicket(Long ticketId, String reason, boolean withDetail,
            String operator) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("TICKET ABANDONNE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Ticket : %s%n", ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format("Caisse : %s%n", ticket.terminalId));
        }
        sb.append(String.format("Date   : %s%n",
                java.time.LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format("Operateur : %s%n", operator));
        }
        // LC-04-04-11: the reason, printed on the paper and not only journalled.
        if (reason != null && !reason.isBlank()) {
            sb.append(String.format("Motif  : %s%n", reason));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        BigDecimal abandoned = BigDecimal.ZERO;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            abandoned = abandoned.add(line.totalPrice == null ? BigDecimal.ZERO : line.totalPrice);
            if (withDetail) {
                String label = line.productLabel.length() > 24
                        ? line.productLabel.substring(0, 24) : line.productLabel;
                sb.append(formatLine(DF.format(line.quantity) + " " + label,
                        DF.format(line.totalPrice) + " E"));
            }
        }
        if (withDetail) {
            sb.append("-".repeat(WIDTH)).append("\n");
        }
        sb.append(formatLine("MONTANT ABANDONNE", DF.format(abandoned) + " E"));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("AUCUN ENCAISSEMENT", WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        return true;
    }

    /**
     * Prints the goods-collection voucher of a closed ticket (LC-02-08-05): the
     * articles marked "à enlever", and the blanks the desk and the customer fill in
     * by hand when the goods change hands.
     *
     * <p>IT IS A HANDOVER SLIP, NOT A RECEIPT. The sale is over and paid; what this
     * paper carries is what has NOT yet been delivered. Hence the two columns nobody
     * prints — quantity actually collected, quantity left to collect — and the date
     * and the two signatures: a customer may come back twice for a garden shed, and
     * the only trace of the first trip is on this sheet. It counts as no print of the
     * ticket and bumps no duplicata counter, exactly like the exchange voucher.
     *
     * <p>Nothing is printed when the sale marked no article, which is the ordinary
     * case: a lane must not spit out an empty slip after every customer.
     *
     * @param ticketId the database id of the closed ticket
     * @return the number of articles the voucher names, zero when none was marked
     */
    @Transactional
    public int printCollectionVoucher(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return 0;
        }
        java.util.List<TicketLine> marked = new java.util.ArrayList<>();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled && line.toCollect) {
                marked.add(line);
            }
        }
        if (marked.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("ARTICLES A ENLEVER", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Ticket : %s%n", ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format("Caisse : %s%n", ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format("Date   : %s%n",
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("ARTICLE", "A RETIRER")).append("\n");
        for (TicketLine line : marked) {
            String label = line.productLabel.length() > 30
                    ? line.productLabel.substring(0, 30) : line.productLabel;
            sb.append(formatLine(label, DF.format(line.quantity)));
            if (line.ean != null && !line.ean.isEmpty()) {
                sb.append("  ").append(line.ean).append("\n");
            }
            // The two blanks the desk fills in. They are per ARTICLE and not per
            // voucher: a customer who collects half a pallet leaves with the same
            // sheet, and the next trip is written on the same line.
            sb.append("  Retire : ............  Reste : ............\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append("Date du retrait : ......../......../........\n\n");
        sb.append("Vendeur  : ..............................\n");
        sb.append("Signature: ..............................\n\n");
        sb.append("Client   : ..............................\n");
        sb.append("Signature: ..............................\n");
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        return marked.size();
    }

    /**
     * Prints a BON POUR ECHANGE of a closed ticket (LC-08-05-10 to -13): the
     * references of the original sale and the articles it names, with their labels
     * and quantities and NO amount at all.
     *
     * <p>Its whole point is the absence of prices: it is what a customer is handed to
     * exchange a gift without being told what it cost. So it prints no unit price, no
     * line total, no ticket total and no VAT — and it is not a receipt: it counts as
     * no print of the ticket and bumps no duplicata counter, exactly like the identity
     * barcode.
     *
     * <p>The selection is a FILTER, not a requirement: an empty or absent selection
     * prints every sold article, which is the plain "duplicata sans prix" of
     * LC-08-05-10; naming lines narrows it down (LC-08-05-12). A cancelled article is
     * outside the sale and never appears either way.
     *
     * @param ticketId the database id of the original ticket
     * @param lineIds the database ids of the lines to print, empty or null for all
     */
    @Transactional
    public void printExchangeVoucher(Long ticketId, java.util.Set<Long> lineIds) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("BON POUR ECHANGE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Ticket : %s%n", ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format("Caisse : %s%n", ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format("Date   : %s%n",
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        boolean whole = lineIds == null || lineIds.isEmpty();
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) continue;
            if (!whole && !lineIds.contains(line.id)) continue;
            String label = line.productLabel.length() > 30
                    ? line.productLabel.substring(0, 30) : line.productLabel;
            sb.append(formatLine(label, DF.format(line.quantity)));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("AUCUN MONTANT NE FIGURE SUR CE BON", WIDTH)).append("\n");
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the card receipt of a refund — a "credit" card transaction
     * (LC-08-03-10). The register does not drive the terminal for a refund
     * (the monetique credit is handled on the terminal itself), so the slip
     * carries what the register knows: the restituted amount and the refund's
     * own references.
     *
     * @param refund the persisted refund whose card credit is receipted
     */
    public void printCardCreditReceipt(Refund refund) {
        StringBuilder sb = new StringBuilder();
        cardHeader(sb, null, "CREDIT");
        sb.append(String.format("Caisse : %s%n", refund.terminalId));
        sb.append(String.format("Date   : %s%n",
                refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("MONTANT CREDITE", DF.format(refund.totalAmount) + " E"));
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Prints the card receipt of a NOT-COMPLETED transaction (LC-08-03-12):
     * the terminal's own TNA frame when it supplied one — printed verbatim,
     * the monetique owns that text — and otherwise a register-built slip. The
     * "ABANDON DEBIT" mention is what makes the slip a TNA proof, so it is
     * printed either way.
     *
     * @param amount the amount the refused transaction was requested for
     * @param frame the terminal's TNA frame, or null when it supplied none
     */
    public void printCardTnaReceipt(BigDecimal amount, String frame) {
        StringBuilder sb = new StringBuilder();
        cardHeader(sb, null, "ABANDON DEBIT");
        if (frame != null && !frame.isBlank()) {
            sb.append(frame);
            if (!frame.endsWith("\n")) {
                sb.append("\n");
            }
        } else if (amount != null) {
            sb.append(formatLine("MONTANT", DF.format(amount) + " E"));
        }
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
    }

    /**
     * Writes the shared head of every card receipt: the banner, the optional
     * store name and the optional mention that qualifies the slip.
     *
     * @param sb the receipt being built
     * @param storeName the store name, or null when unavailable
     * @param mention the qualifying mention (credit, TNA), or null for a plain
     *        sale receipt
     */
    private void cardHeader(StringBuilder sb, String storeName, String mention) {
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        if (storeName != null) {
            sb.append(center(storeName, WIDTH)).append("\n");
        }
        sb.append(center("TICKET CARTE BANCAIRE", WIDTH)).append("\n");
        if (mention != null) {
            sb.append(center("*** " + mention + " ***", WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
    }
}
