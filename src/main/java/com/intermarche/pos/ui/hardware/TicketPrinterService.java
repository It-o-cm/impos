package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.payment.CardPayment;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.sale.*;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketPrinterService.class);

    /** The back-office parameters (EAN on paper, LC-02-04-04). */
    @jakarta.inject.Inject
    PosSettingsService posSettingsService;

    @Inject
    HardwareService hardwareService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * The administered layouts (BO-03-03). Null when this printer was built by
     * hand in a unit test, which simply means "no template, print it our way" —
     * the same answer the service itself gives for an unadministered document.
     */
    @Inject
    com.intermarche.pos.service.DocumentTemplateService documentTemplateService;

    /**
     * Renders a document through its ADMINISTERED layout, when there is one
     * (BO-03-03).
     *
     * @param type the document being printed
     * @param data the already-formatted values the layout may read
     * @return the laid-out document, or null when this printer must render it
     */
    private String administeredLayout(
            com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type,
            java.util.Map<String, Object> data) {
        if (documentTemplateService == null) {
            return null;
        }
        return documentTemplateService.render(type, data);
    }

    /** French display format for amounts on the receipt. */
    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** Standard 80mm receipt width, in characters. */
    private static final int WIDTH = 42;

    /**
     * The typesetting primitives of this register's paper, shared with the
     * template renderer so a factory template lays out exactly as this class
     * does rather than re-implementing the alignment.
     */
    private static final com.intermarche.pos.service.DocumentLayout LAYOUT =
            new com.intermarche.pos.service.DocumentLayout(WIDTH);

    /**
     * Loads a ticket from the database and prints it. The first print is the
     * original; any further print is marked "DUPLICATA N°x", counted on the
     * ticket and recorded in the journal.
     *
     * @param ticketId the database id of the ticket to print
     */
    @Transactional
    public void printTicket(Long ticketId) {
        LOGGER.info("Entering method printTicket with ticketId: " + ticketId);
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
        LOGGER.info("Exiting method printTicket");
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
        LOGGER.info("Entering method renderTicket with ticket: " + ticket + ", duplicata: " + duplicata + ", duplicataNumber: " + duplicataNumber);
        // BO-03-03: the store's own layout first. A shop that administers none —
        // or one whose layout does not hold together — gets the receipt below,
        // character for character as before.
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                saleDocumentData(ticket, duplicata, duplicataNumber));
        if (administered != null) {
            LOGGER.info("Exiting method renderTicket");
            return administered;
        }
        StringBuilder sb = new StringBuilder();
        // Header. The logo prints above the store name when this till has one; the
        // text line stays regardless, so a receipt is readable even on a printer that
        // ignores images or a deployment with no logo file.
        sb.append(logoHeader());
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
        // Footer-placed engine advantages (GB-01-07-10): a discount whose
        // printMode is FOOTER — or absent, the default — is collected here as its
        // article is walked and printed in the totals block below rather than
        // under the line. Only an explicit LINE placement prints under the line.
        StringBuilder footerAdvantages = new StringBuilder();
        // LC-08-01-07: the store administers the order the articles print in, and
        // whether they are grouped under a family heading. The stored lines keep
        // their own order — this is a rendering, not the record.
        String lineOrder = TicketLineOrder.normalize(posSettingsService.ticketLineOrder());
        boolean groupByFamily = TicketLineOrder.groupsByFamily(lineOrder);
        String printedFamily = null;
        // EXP-43: the running sum since the previous SOUS-TOTAL marker, printed at
        // each boundary so each shopper of a shared basket reads their part.
        java.math.BigDecimal subtotalRunning = java.math.BigDecimal.ZERO;
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, lineOrder)) {
            // A cancelled article (lot C4, BO-04-01-16) is kept in the ticket
            // only as a journal witness; it never prints on the receipt.
            if (line.cancelled) continue;
            subtotalRunning = subtotalRunning.add(line.totalPrice);
            if (groupByFamily) {
                String family = TicketLineOrder.familyOf(line);
                if (!family.equals(printedFamily)) {
                    sb.append(center(family, WIDTH)).append("\n");
                    printedFamily = family;
                }
            }
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // P3-5 / LC-02-05-04 / LC-02-13-18: the label, then ONE decomposition
            // sub-line "quantité × prix unitaire" beneath it — the very line the two
            // screens show, built by the shared formatter (euro glyph "E" on paper).
            // Covers the four cases (piece, measure unit, weighed, price-embedded
            // deduced weight); empty — so absent — for a single unit sold by the piece.
            sb.append(String.format("%-20s%n", label));
            String detail = com.intermarche.pos.ui.ticket.TicketState.detailLineOf(
                    line.quantity, line.unitPrice, line.unitName,
                    line.plu != null && !line.plu.isEmpty(),
                    line.embeddedWeight, line.embeddedUnitPrice, "E");
            if (!detail.isBlank()) {
                sb.append("  ").append(detail).append("\n");
            }
            if (posSettingsService.showEan() && line.ean != null && !line.ean.isEmpty()) {
                // LC-02-04-04: the EAN printed under the label when configured.
                sb.append("  ").append(line.ean).append("\n");
            }
            // Line total (right-aligned)
            String lineTotal = DF.format(line.totalPrice);
            sb.append(String.format("%" + WIDTH + "s%n", lineTotal));
            // Engine advantage on this line: printed as a signed delta, and
            // the CORRECTION on its own line. The two are separated because
            // they go opposite ways: printing a ticket that went up under an
            // advantage label would tell the customer the exact contrary of
            // what happened to their money.
            TicketLineValuation valuation = valuations.get(line.lineUid);
            if (valuation != null) {
                java.math.BigDecimal correction = valuation.correctionAmount != null
                        ? valuation.correctionAmount : java.math.BigDecimal.ZERO;
                java.math.BigDecimal delta = valuation.valuedTotal
                        .subtract(valuation.localTotal).subtract(correction);
                if (delta.signum() != 0) {
                    String advLabel = valuation.advantageLabel != null ? valuation.advantageLabel
                            : (valuation.offerLabel != null ? valuation.offerLabel : "AVANTAGE");
                    if (advLabel.length() > 26) advLabel = advLabel.substring(0, 26);
                    // LINE places it under the article; FOOTER — or a null
                    // placement, the default — defers it to the totals block.
                    if ("LINE".equals(valuation.advantagePrintMode)) {
                        sb.append(formatLine("  " + advLabel, DF.format(delta) + " E"));
                    } else {
                        footerAdvantages.append(formatLine(advLabel, DF.format(delta) + " E"));
                    }
                }
                if (correction.signum() > 0) {
                    String corLabel = valuation.correctionLabel != null
                            ? valuation.correctionLabel : "AJUSTEMENT";
                    if (corLabel.length() > 26) corLabel = corLabel.substring(0, 26);
                    sb.append(formatLine("  " + corLabel, "+" + DF.format(correction) + " E"));
                }
            }
            // EXP-43: the SOUS-TOTAL drawn after this line, summing the lines since
            // the previous marker. A ticket with no marker prints exactly as before.
            if (line.subtotalBoundary) {
                sb.append(formatLine("SOUS-TOTAL", DF.format(subtotalRunning) + " E"));
                subtotalRunning = java.math.BigDecimal.ZERO;
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Totals
        // Footer-placed engine advantages (GB-01-07-10), each under its own
        // label, before the ticket-level discount and the grand total. Empty —
        // no advantage, or all of them placed under their line — prints nothing.
        sb.append(footerAdvantages);
        if (ticket.globalDiscountApplied != null) {
            sb.append(formatLine("REMISE TICKET",
                    "-" + DF.format(ticket.globalDiscountApplied) + " E"));
        }
        sb.append(formatLine("TOTAL TTC", DF.format(ticket.totalIncludingTax) + " E"));
        // Loyalty section (imfid lot 3): the DISPLAYED projection and its rule
        // labels — the only rule data that travels to the POS (spec §3),
        // indicative by doctrine (the ingestion's recomputation prevails).
        // It is read from the TICKET, where the closing froze it, so a
        // duplicata — here or on the store node — restates the very figures the
        // customer was shown rather than the projection of whatever sale the
        // register happens to be on.
        // BO-10-03-15: the advantage section is printed only when the back
        // office activated the fidelity advantages; disabled, no section.
        if (posSettingsService.fidelityAdvantagesEnabled()
                && ticket.fidelityEarnTotal != null
                && ticket.fidelityEarnTotal.signum() > 0) {
            if (ticket.fidelityAnonymousProjection) {
                // imfid response C: a card-less ticket shows what a card would
                // have earned. No balances, no "cagnotte du jour".
                for (String messageLine
                        : wrap("Avec la carte, vous auriez gagne :", WIDTH)) {
                    sb.append(messageLine).append("\n");
                }
                sb.append(formatLine("  CAGNOTTE",
                        "+" + DF.format(ticket.fidelityEarnTotal) + " E"));
            } else {
                sb.append(formatLine("CAGNOTTE DU JOUR",
                        "+" + DF.format(ticket.fidelityEarnTotal) + " E"));
                appendFidelityAdvantages(sb, ticket);
                // imfid response B: the forecast balance after the sale, under an
                // administered label, marked PREVISIONNEL — nothing is credited
                // before the ingestion.
                if (ticket.fidelityProjectedAfterEarn != null) {
                    sb.append(formatLine(posSettingsService.fidelityProjectedLabel(),
                            DF.format(ticket.fidelityProjectedAfterEarn) + " E"));
                    sb.append(center("(PREVISIONNEL)", WIDTH)).append("\n");
                }
            }
        }
        // BO-03-03-29/30: the loyalty service did not answer during the sale, so
        // the shop's message takes the place of the advantages that could not be
        // computed -- one text for a holder ("your advantages will follow"),
        // another for a non-holder ("present your card next time"). It is wrapped
        // to the paper width rather than truncated: a sentence cut in half on a
        // receipt says nothing at all. The administered layout already received
        // it through the document data; the built-in layout below is what prints
        // until a template is administered, and it was the one staying silent.
        String fidelityMessage = unavailableMessage(ticket,
                ticket.fidelityCard != null && !ticket.fidelityCard.isBlank());
        if (!fidelityMessage.isEmpty()) {
            for (String messageLine : wrap(fidelityMessage, WIDTH)) {
                sb.append(messageLine).append("\n");
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
            if (payment instanceof com.intermarche.pos.domain.payment.ForeignCurrencyPayment devise) {
                sb.append(formatLine("DEVISE " + safe(devise.currencyCode),
                        DF.format(payment.amount) + " E"));
                sb.append("  ").append(DF.format(devise.foreignAmount)).append(" ")
                        .append(safe(devise.currencyCode)).append("  TAUX ")
                        .append(devise.exchangeRate == null ? "" : devise.exchangeRate.toPlainString())
                        .append("\n");
                continue;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.RoundingPayment) {
                sb.append(formatLine("ARRONDI", DF.format(payment.amount.negate()) + " E"));
                continue;
            }
            sb.append(formatLine(method, DF.format(payment.amount) + " E"));
            // LC-08-04-06 [sic LC-07-09-06]: a credit settlement names its debtor
            // under its own line — date, account number, account name. A month
            // later this receipt is what the account is reconciled against, and an
            // amount owed by nobody cannot be reconciled against anything.
            if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment credit) {
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
        // GB-01-07-05/06: the engine offer messages, in the ticket footer (the
        // default placement, and the only sensible one for a basket-level
        // message), each wrapped to the paper width. No messages — the general
        // case — prints nothing here.
        if (ticket.messages != null) {
            for (com.intermarche.pos.domain.sale.TicketMessage message : ticket.messages) {
                if (message.text == null || message.text.isBlank()) {
                    continue;
                }
                for (String messageLine : wrap(message.text, WIDTH)) {
                    sb.append(center(messageLine, WIDTH)).append("\n");
                }
            }
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
        LOGGER.info("Exiting method renderTicket");
        return sb.toString();
    }

    /**
     * Builds the REFUND as an administered layout sees it (BO-03-03).
     *
     * @param refund the refund to describe
     * @param original the refunded sale, or null when it could not be loaded
     * @return the document values
     */
    java.util.Map<String, Object> refundDocumentData(Refund refund, Ticket original) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", original == null ? emptyStore() : storeData(original));
        data.put("terminal", safe(refund.terminalId));
        data.put("operator", "");
        data.put("date", refund.creationDate == null ? ""
                : refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", refund.creationDate == null ? ""
                : refund.creationDate.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(refund.refundNumber),
                "originalNumber", original == null ? "" : safe(original.ticketNumber),
                "method", refund.refundMethod == null ? ""
                        : refundMethodLabel(refund.refundMethod),
                "returnReason", safe(refund.returnReason)));
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        VatBreakdown breakdown = new VatBreakdown();
        if (refund.lines != null) {
            for (RefundLine line : refund.lines) {
                BigDecimal total = line.price == null || line.quantity == null
                        ? BigDecimal.ZERO
                        : line.price.multiply(line.quantity)
                                .setScale(2, java.math.RoundingMode.HALF_UP);
                breakdown.add(line.vatRate, total);
                lines.add(java.util.Map.of(
                        "label", safe(line.productLabel),
                        "quantity", line.quantity == null ? "" : DF.format(line.quantity),
                        "unitPrice", line.price == null ? "" : DF.format(line.price),
                        "total", DF.format(total),
                        "vatRate", line.vatRate == null ? "" : line.vatRate.toPlainString()));
            }
        }
        data.put("lines", lines);
        data.put("totals", java.util.Map.of("includingTax",
                refund.totalAmount == null ? "" : DF.format(refund.totalAmount)));
        java.util.List<java.util.Map<String, Object>> vatRows = new java.util.ArrayList<>();
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            vatRows.add(java.util.Map.of(
                    "rate", bucket.getRateFormatted(),
                    "base", DF.format(bucket.totalExcludingTax),
                    "vat", DF.format(bucket.vatAmount)));
        }
        data.put("vatRows", vatRows);
        return data;
    }

    /**
     * Builds a CASH MOVEMENT as an administered layout sees it — a withdrawal
     * or a transfer (BO-03-03).
     *
     * @param label what the movement is called on paper
     * @param tender the tender moved, or null
     * @param from the tender a transfer leaves, or null
     * @param to the tender a transfer reaches, or null
     * @param amount the amount moved
     * @param counts the per-denomination detail of a withdrawal, or null
     * @param operator the operator who signed it, or null
     * @param terminalId the register it happened on, or null
     * @return the document values
     */
    java.util.Map<String, Object> movementDocumentData(String label, String tender,
            String from, String to, BigDecimal amount, java.util.List<String[]> counts,
            String operator, String terminalId) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", emptyStore());
        data.put("terminal", safe(terminalId));
        data.put("operator", safe(operator));
        data.put("date", java.time.LocalDate.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", java.time.LocalTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("movement", java.util.Map.of(
                "label", safe(label),
                "tender", safe(tender),
                "from", safe(from),
                "to", safe(to),
                "amount", amount == null ? "" : DF.format(amount)));
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        if (counts != null) {
            for (String[] count : counts) {
                rows.add(java.util.Map.of("label", safe(count[0]), "amount", safe(count[1])));
            }
        }
        data.put("counts", rows);
        return data;
    }

    /**
     * Builds a CARD RECEIPT as an administered layout sees it (BO-03-03).
     *
     * @param kind what the slip states — a credit, an abandoned debit
     * @param amount the amount, or null
     * @param frame the monetique frame printed verbatim, or null
     * @param terminalId the register it happened on, or null
     * @param moment when it happened, already written, or null
     * @return the document values
     */
    java.util.Map<String, Object> cardDocumentData(String kind, BigDecimal amount,
            String frame, String terminalId, String moment) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", emptyStore());
        data.put("terminal", safe(terminalId));
        data.put("operator", "");
        data.put("date", safe(moment));
        data.put("time", "");
        data.put("card", java.util.Map.of(
                "kind", safe(kind),
                "amount", amount == null ? "" : DF.format(amount),
                "frame", safe(frame)));
        return data;
    }

    /**
     * Describes a point of sale a document carries nothing about.
     *
     * @return the store values, all empty
     */
    private java.util.Map<String, Object> emptyStore() {
        return java.util.Map.of("name", "", "street", "", "postalCode", "",
                "city", "", "siret", "");
    }

    /**
     * Builds the SESSION REPORT as an administered layout sees it (BO-03-03).
     *
     * <p>The tender list is the one the report already decided to detail
     * (BO-03-02-25): a layout states what the referential says is statable, and
     * the lump total of the rest is handed over beside it.
     *
     * @param report the report to describe
     * @return the document values
     */
    java.util.Map<String, Object> sessionDocumentData(CashSessionService.SessionReport report) {
        var session = report.session;
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", emptyStore());
        data.put("terminal", session == null ? "" : safe(session.terminalId));
        data.put("operator", session == null || session.closingCashier == null ? ""
                : safe(session.closingCashier.getFullName()));
        data.put("date", java.time.LocalDate.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", java.time.LocalTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("session", java.util.Map.of(
                "number", session == null ? "" : safe(session.sessionNumber),
                "openedAt", session == null || session.openingDate == null ? ""
                        : session.openingDate.format(
                                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                "closedAt", session == null || session.closingDate == null ? ""
                        : session.closingDate.format(
                                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                "closing", report.closing));
        data.put("totals", java.util.Map.of(
                "ticketCount", String.valueOf(report.ticketCount),
                "includingTax", report.getTotalIncludingTaxFormatted(),
                "refunds", report.getTotalRefundsFormatted(),
                "theoreticalCash", report.getTheoreticalCashFormatted(),
                "openingFloat", session == null || session.openingFloat == null ? ""
                        : DF.format(session.openingFloat),
                "countedCash", session == null || session.countedAmount == null ? ""
                        : DF.format(session.countedAmount),
                "variance", session == null || session.variance == null ? ""
                        : DF.format(session.variance),
                "withdrawn", session == null || session.withdrawnAmount == null ? ""
                        : DF.format(session.withdrawnAmount),
                "otherTenders", report.getOtherMethodsTotalFormatted(),
                "bankDeposit", report.getBankDepositTotalFormatted()));
        java.util.List<java.util.Map<String, Object>> tenders = new java.util.ArrayList<>();
        for (CashSessionService.SessionReport.MethodRow row : report.getDetailedMethodRows()) {
            tenders.add(java.util.Map.of("label", row.method(), "amount", row.amountFormatted()));
        }
        data.put("tenders", tenders);
        return data;
    }

    /**
     * Builds a STORED-VALUE VOUCHER as an administered layout sees it — a gift
     * card or a credit note (BO-03-03).
     *
     * @param number the registry number of the instrument
     * @param amount the amount it carries
     * @return the document values
     */
    java.util.Map<String, Object> instrumentDocumentData(String number, BigDecimal amount) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", emptyStore());
        data.put("terminal", "");
        data.put("operator", "");
        data.put("date", java.time.LocalDate.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", java.time.LocalTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("instrument", java.util.Map.of(
                "number", safe(number),
                "amount", amount == null ? "" : DF.format(amount)));
        return data;
    }

    /**
     * Builds the SALE as an administered layout sees it (BO-03-03).
     *
     * <p>Maps, lists and strings, every figure already written — the shape the
     * back office's preview pane advertises, built here from a real sale. A
     * layout therefore reads exactly what the paramétreur was shown, and cannot
     * reach anything else.
     *
     * <p>Every accessor is null-tolerant on purpose: this printer is also built
     * by hand in unit tests and reached from the store node, where a sale may
     * carry no store, no cashier or no payment, and a layout must not be the
     * thing that fails a print.
     *
     * @param ticket the sale to describe
     * @param duplicata whether this print is a duplicate
     * @param duplicataNumber the duplicate's rank, meaningless when not one
     * @return the document values
     */
    java.util.Map<String, Object> saleDocumentData(Ticket ticket, boolean duplicata,
            int duplicataNumber) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", storeData(ticket));
        data.put("terminal", safe(ticket.terminalId));
        data.put("operator", ticket.cashier == null ? "" : safe(ticket.cashier.getFullName()));
        data.put("date", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(ticket.ticketNumber),
                "duplicate", duplicata,
                "duplicateNumber", String.valueOf(duplicataNumber),
                "digitalPath", ticket.digitalKey == null ? ""
                        : "/t/" + ticket.id + "/" + ticket.digitalKey));
        data.put("lines", saleLines(ticket));
        data.put("totals", saleTotals(ticket));
        data.put("vatRows", saleVatRows(ticket));
        data.put("payments", salePayments(ticket));
        data.put("fidelity", fidelityData(ticket));
        return data;
    }

    /**
     * Describes the LOYALTY ZONE of a document, from what the closing froze on
     * the sale (BO-03-03-25, -29, -30, -31, -32).
     *
     * <p>Everything a layout may say about the programme is here and nothing
     * else: the card, whether one was presented, the earn of the day and its
     * advantage lines, the balance read at attachment, the amount settled on
     * that balance, and — when the service did not answer — the administered
     * message, its {@code {carte}} token already replaced. The layout decides
     * where each goes; it never decides WHAT, and it cannot reach a figure the
     * register did not vouch for.
     *
     * @param ticket the sale to describe
     * @return the loyalty values, every one of them already formatted
     */
    /**
     * Appends the frozen advantage lines of a sale, GROUPED by advantage type
     * and the groups ordered by {@code advantageTypeOrder} ascending (imfid
     * response A, BO-03-03-25/-33). The register prints the received labels
     * verbatim and never translates a code, and it orders by the received
     * order, never by any evaluation priority.
     * <p>
     * When NO line carries a type (rules predating the referential), the output
     * is the flat list exactly as before this grouping existed — so an
     * old-ticket duplicata is byte-identical. When some lines carry a type they
     * are grouped and ordered, and any remaining untyped lines are appended
     * flat after the groups.
     *
     * @param sb the receipt buffer
     * @param ticket the sale whose frozen advantage lines are printed
     */
    private void appendFidelityAdvantages(StringBuilder sb, Ticket ticket) {
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> allLines =
                ticket.fidelityLines == null ? java.util.List.of() : ticket.fidelityLines;
        boolean anyTyped = false;
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType != null) {
                anyTyped = true;
                break;
            }
        }
        if (!anyTyped) {
            for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
                sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
            }
            return;
        }
        java.util.LinkedHashMap<String,
                java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>> groups =
                new java.util.LinkedHashMap<>();
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> untyped =
                new java.util.ArrayList<>();
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
            if (line.advantageType == null) {
                untyped.add(line);
            } else {
                groups.computeIfAbsent(line.advantageType, key -> new java.util.ArrayList<>()).add(line);
            }
        }
        java.util.List<String> orderedKeys = new java.util.ArrayList<>(groups.keySet());
        orderedKeys.sort(java.util.Comparator.comparingInt(key -> groupOrder(groups.get(key))));
        for (String key : orderedKeys) {
            java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines = groups.get(key);
            String header = lines.get(0).advantageTypeLabel;
            sb.append(header == null ? key : header).append("\n");
            for (com.intermarche.pos.domain.sale.TicketFidelityLine line : lines) {
                sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
            }
        }
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : untyped) {
            sb.append(formatLine("  " + line.getLabel(), "+" + DF.format(line.amount) + " E"));
        }
    }

    /**
     * The print order of an advantage-type group: the {@code advantageTypeOrder}
     * of its first line, or {@link Integer#MAX_VALUE} when the group carries
     * none (so unordered groups fall to the end).
     *
     * @param lines the group's lines, never empty
     * @return the ordering key
     */
    private int groupOrder(java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines) {
        Integer order = lines.get(0).advantageTypeOrder;
        return order == null ? Integer.MAX_VALUE : order;
    }

    private java.util.Map<String, Object> fidelityData(Ticket ticket) {
        boolean present = ticket.fidelityCard != null && !ticket.fidelityCard.isBlank();
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line
                : ticket.fidelityLines == null
                        ? java.util.List.<com.intermarche.pos.domain.sale.TicketFidelityLine>of()
                        : ticket.fidelityLines) {
            java.util.Map<String, Object> lineData = new java.util.HashMap<>();
            lineData.put("ruleCode", safe(line.ruleCode));
            lineData.put("label", line.getLabel());
            lineData.put("amount", line.getAmountFormatted());
            // imfid response A: the advantage grouping travels to the administered
            // layout too, so a store template can group and order identically.
            lineData.put("advantageType", safe(line.advantageType));
            lineData.put("advantageTypeLabel", safe(line.advantageTypeLabel));
            lineData.put("advantageTypeOrder", line.advantageTypeOrder == null
                    ? "" : String.valueOf(line.advantageTypeOrder));
            lineData.put("advantageCategory", safe(line.advantageCategory));
            lineData.put("advantageCategoryLabel", safe(line.advantageCategoryLabel));
            lines.add(lineData);
        }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("card", safe(ticket.fidelityCard));
        data.put("present", present);
        data.put("earnTotal", ticket.fidelityEarnTotal == null
                ? "" : DF.format(ticket.fidelityEarnTotal));
        data.put("lines", lines);
        data.put("availableBalance", ticket.fidelityAvailableBalance == null
                ? "" : DF.format(ticket.fidelityAvailableBalance));
        // imfid response B/C: projected balance (PREVISIONNEL), ledger balance
        // after the burn, and the anonymous-projection flag.
        data.put("projectedAfterEarn", ticket.fidelityProjectedAfterEarn == null
                ? "" : DF.format(ticket.fidelityProjectedAfterEarn));
        data.put("projectedLabel", posSettingsService.fidelityProjectedLabel());
        data.put("balanceAfter", ticket.fidelityBalanceAfter == null
                ? "" : DF.format(ticket.fidelityBalanceAfter));
        data.put("anonymousProjection", ticket.fidelityAnonymousProjection);
        data.put("usedAmount", DF.format(loyaltyPaid(ticket)));
        data.put("unavailable", ticket.fidelityUnavailable);
        data.put("message", unavailableMessage(ticket, present));
        return data;
    }

    /**
     * The amount this sale settled on the loyalty balance.
     *
     * <p>Summed over the settlements rather than read from a column: a sale may
     * carry several loyalty settlements, and the one figure the customer checks
     * is what left the cagnotte in total.
     *
     * @param ticket the sale
     * @return the settled amount, zero when none was taken on the balance
     */
    private BigDecimal loyaltyPaid(Ticket ticket) {
        BigDecimal used = BigDecimal.ZERO;
        if (ticket.payments == null) {
            return used;
        }
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.pos.domain.payment.FidelityPayment
                    && payment.amount != null) {
                used = used.add(payment.amount);
            }
        }
        return used;
    }

    /**
     * The administered message a receipt carries when the loyalty service did
     * not answer during the sale (BO-03-03-29, BO-03-03-30).
     *
     * <p>Two messages because they say two different things: a holder is told
     * their advantages will follow, a non-holder is invited to present a card
     * next time. A shop that clears the parameter says nothing, which is the
     * historical behaviour and stays available.
     *
     * @param ticket the sale
     * @param present whether a card was presented
     * @return the resolved message, empty when the service answered or the shop
     *         administers none
     */
    private String unavailableMessage(Ticket ticket, boolean present) {
        if (!ticket.fidelityUnavailable) {
            return "";
        }
        String message = present
                ? posSettingsService.fidelityOfflineMessage()
                : posSettingsService.fidelityOfflineMessageNoCard();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace("{carte}", safe(ticket.fidelityCard));
    }

    /**
     * Describes the point of sale a document is emitted by.
     *
     * @param ticket the sale
     * @return the store values, empty strings when the sale carries no store
     */
    private java.util.Map<String, Object> storeData(Ticket ticket) {
        if (ticket.store == null) {
            return java.util.Map.of("name", "", "street", "", "postalCode", "",
                    "city", "", "siret", "");
        }
        com.intermarche.pos.domain.store.Address address = ticket.store.address;
        return java.util.Map.of(
                "name", safe(ticket.store.name),
                "street", address == null ? "" : safe(address.streetLine1),
                "postalCode", address == null ? "" : safe(address.postalCode),
                "city", address == null ? "" : safe(address.city),
                "siret", safe(ticket.store.siret));
    }

    /**
     * Describes the articles a layout prints, in the administered print order
     * and without the cancelled ones (LC-08-01-07, BO-04-01-16).
     *
     * @param ticket the sale
     * @return one map per printed article
     */
    private java.util.List<java.util.Map<String, Object>> saleLines(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        if (ticket.lines == null) {
            return lines;
        }
        String order = TicketLineOrder.normalize(posSettingsService.ticketLineOrder());
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, order)) {
            if (line.cancelled) continue;
            lines.add(java.util.Map.of(
                    "label", safe(line.productLabel),
                    "ean", safe(line.ean),
                    "family", safe(TicketLineOrder.familyOf(line)),
                    "unit", safe(line.unitName),
                    "quantity", line.quantity == null ? "" : DF.format(line.quantity),
                    "unitPrice", line.unitPrice == null ? "" : DF.format(line.unitPrice),
                    "total", line.totalPrice == null ? "" : DF.format(line.totalPrice),
                    "vatRate", line.vatRate == null ? "" : line.vatRate.toPlainString()));
        }
        return lines;
    }

    /**
     * Describes what the sale comes to.
     *
     * @param ticket the sale
     * @return the totals, already written
     */
    private java.util.Map<String, Object> saleTotals(Ticket ticket) {
        BigDecimal includingTax = ticket.totalIncludingTax == null
                ? BigDecimal.ZERO : ticket.totalIncludingTax;
        BigDecimal vat = ticket.totalVat == null ? BigDecimal.ZERO : ticket.totalVat;
        return java.util.Map.of(
                "excludingTax", DF.format(includingTax.subtract(vat)),
                "vat", DF.format(vat),
                "includingTax", DF.format(includingTax),
                "discount", ticket.globalDiscountApplied == null ? ""
                        : DF.format(ticket.globalDiscountApplied));
    }

    /**
     * Describes the per-rate VAT ventilation, on the same rule as the persisted
     * totals.
     *
     * @param ticket the sale
     * @return one map per VAT rate
     */
    private java.util.List<java.util.Map<String, Object>> saleVatRows(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        VatBreakdown breakdown = new VatBreakdown();
        if (ticket.lines != null) {
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) continue;
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            rows.add(java.util.Map.of(
                    "rate", bucket.getRateFormatted(),
                    "base", DF.format(bucket.totalExcludingTax),
                    "vat", DF.format(bucket.vatAmount)));
        }
        return rows;
    }

    /**
     * Describes how the sale was settled, the rounding carried with the sign
     * the paper reads rather than the one the ledger stores (LC-07-03-06).
     *
     * @param ticket the sale
     * @return one map per settlement
     */
    private java.util.List<java.util.Map<String, Object>> salePayments(Ticket ticket) {
        java.util.List<java.util.Map<String, Object>> payments = new java.util.ArrayList<>();
        if (ticket.payments == null) {
            return payments;
        }
        for (TicketPayment payment : ticket.payments) {
            BigDecimal amount = payment.amount == null ? BigDecimal.ZERO : payment.amount;
            boolean rounding =
                    payment instanceof com.intermarche.pos.domain.payment.RoundingPayment;
            payments.add(java.util.Map.of(
                    "label", payment.getClass().getSimpleName()
                            .replace("Payment", "").toUpperCase(),
                    "key", safe(payment.getMethodKey()),
                    "amount", DF.format(rounding ? amount.negate() : amount)));
        }
        return payments;
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
        LOGGER.info("Entering method printRenderedTicket with content: " + content);
        if (content == null || content.isBlank()) {
            LOGGER.info("Exiting method printRenderedTicket");
            return;
        }
        hardwareService.printReceipt(content);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printRenderedTicket");
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
        LOGGER.info("Entering method printSessionReport with report: " + report);
        var session = report.session;
        // BO-03-03: the X and the Z are two documents, so they carry two layouts
        // — a shop may restate its closing without touching its reading.
        String administered = administeredLayout(report.closing
                        ? com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.Z_REPORT
                        : com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.X_REPORT,
                sessionDocumentData(report));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printSessionReport");
            return;
        }
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
        // BO-03-02-25: a tender the back office does not put in detail is not
        // hidden, it is summed — the report still balances, it just says less
        // about tenders the store does not want line by line.
        for (var row : report.getDetailedMethodRows()) {
            sb.append(formatLine(row.method(), row.amountFormatted() + " E"));
        }
        if (report.isCarryingUndetailedMethods()) {
            sb.append(formatLine("Autres reglements",
                    report.getOtherMethodsTotalFormatted() + " E"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report.totalRefunds.signum() > 0) {
            sb.append(formatLine("Remboursements", DF.format(report.totalRefunds) + " E"));
        }
        // BO-03-02-21 and BO-03-02-30: what goes to the bank and what goes to the
        // fidelity programme are stated only when the store administered tenders
        // for them — a nil line would say something the store never asked.
        if (report.bankDepositTotal.signum() > 0) {
            sb.append(formatLine("Remise en banque",
                    report.getBankDepositTotalFormatted() + " E"));
        }
        if (report.fidelityReportedTotal.signum() > 0) {
            sb.append(formatLine("Dont remontee fidelite",
                    report.getFidelityReportedTotalFormatted() + " E"));
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
        LOGGER.info("Exiting method printSessionReport");
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
        LOGGER.info("Entering method printRefund with refundId: " + refundId);
        Refund refund = Refund.findById(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("Remboursement introuvable pour impression : " + refundId);
        }
        Ticket original = Ticket.findById(refund.originalTicketId);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.REFUND_RECEIPT,
                refundDocumentData(refund, original));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printRefund");
            return;
        }
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
        // LC-05-05-03: the chosen return reason is printed on the return ticket
        // when the store administers it (return.print-reason) and a reason was
        // actually chosen; otherwise the paper stays as before.
        if (posSettingsService.printReturnReason()
                && refund.returnReason != null && !refund.returnReason.isBlank()) {
            sb.append(String.format("Motif  : %s%n", refund.returnReason));
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
        LOGGER.info("Exiting method printRefund");
    }

    /**
     * The logo line opening a printed document, or nothing.
     *
     * <p>Every document this register hands over carries the enseigne's logo,
     * for the reason the sale receipt does: a paper given to a customer, or
     * passed between an operator and a colleague, is recognised before it is
     * read. A till with no logo file prints the document unchanged.
     *
     * @return the logo directive AND its newline, or an empty string when this
     *         till has no logo
     */
    private String logoHeader() {
        String logo = ReceiptLogo.directive();
        return logo.isEmpty() ? "" : logo + "\n";
    }

    /**
     * The enseigne's banner on a document that names the enseigne.
     *
     * <p>The logo REPLACES the printed name rather than sitting above it: one
     * banner per document. The name stays as the FALLBACK, because an avoir or
     * a loyalty credit carrying no issuer at all is a paper nobody can place -
     * and a till without a logo file, or a printer that drops images, is
     * exactly the case this document must survive.
     *
     * <p>The sale receipt does the opposite: it prints the logo AND the
     * enseigne's name, by the choice its own header comment records. The two
     * conventions differ, and this is the place that says so.
     *
     * @return the banner line, newline included
     */
    private String brandBanner() {
        String logo = ReceiptLogo.directive();
        return logo.isEmpty() ? center("INTERMARCHE", WIDTH) + "\n" : logo + "\n";
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
        LOGGER.info("Entering method printTrainingReceipt with state: " + state);
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
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
        LOGGER.info("Exiting method printTrainingReceipt");
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
    public void printRefundVoucher(Refund refund, String number, java.time.LocalDateTime validUntil) {
        LOGGER.info("Entering method printRefundVoucher with refund: " + refund + ", number: " + number + ", validUntil: " + validUntil);
        // LC-05-06-07: the AVOIR carries a scannable Code-128 of its registry
        // number (for reprise d'avoir) and, when set, an end of validity.
        String validityLine = validUntil == null ? "" : String.format("Valable jusqu'au : %s%n",
                validUntil.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        String sb = brandBanner() +
                center("AVOIR", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine("MONTANT", DF.format(refund.totalAmount) + " E") +
                String.format("Emis le : %s%n",
                        refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))) +
                validityLine +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                center(barcode(number), WIDTH) + "\n" +
                center("(scannable en caisse - solde au registre)", WIDTH) + "\n" +
                "\n";
        hardwareService.printReceipt(sb);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printRefundVoucher");
    }

    /**
     * Prints the customer's proof of a voluntary loyalty refund (imfid spec
     * §28): the amount credited to the card balance at ingestion — the
     * printed slip is the in-hand evidence while the outbox travels.
     *
     * @param amount the amount refunded to the loyalty balance
     */
    public void printLoyaltyCredit(java.math.BigDecimal amount) {
        LOGGER.info("Entering method printLoyaltyCredit with amount: " + amount);
        String sb = brandBanner() +
                center("REMBOURSEMENT EN CAGNOTTE", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine("CREDIT CARTE", "+" + DF.format(amount) + " E") +
                center("(visible sur la carte sous quelques minutes)", WIDTH) + "\n" +
                "\n";
        hardwareService.printReceipt(sb);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printLoyaltyCredit");
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
        LOGGER.info("Entering method printGiftCardVoucher with number: " + number + ", amount: " + amount);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER,
                instrumentDocumentData(number, amount));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printGiftCardVoucher");
            return;
        }
        String sb = center("INTERMARCHE", WIDTH) + "\n" +
                center("CARTE CADEAU", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine("MONTANT CHARGE", DF.format(amount) + " E") +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                center("(scannable en caisse - solde au registre)", WIDTH) + "\n" +
                "\n";
        hardwareService.printReceipt(sb);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printGiftCardVoucher");
    }

    /**
     * Prints the credit note a sale handed over instead of cash change, when
     * the settling tender is administered to give its change back in vouchers
     * (BO-03-02-16).
     *
     * <p>Says what it is on its face — change, not a refund: the customer must
     * be able to tell a note born of an overpayment from one born of a return,
     * because the two are argued about at the desk on different grounds.
     *
     * @param number the registry number of the note
     * @param amount the change it carries
     */
    public void printChangeVoucher(String number, java.math.BigDecimal amount) {
        LOGGER.info("Entering method printChangeVoucher with number: " + number + ", amount: " + amount);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER,
                instrumentDocumentData(number, amount));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printChangeVoucher");
            return;
        }
        String sb = center("INTERMARCHE", WIDTH) + "\n" +
                center("AVOIR - RENDU DE MONNAIE", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine("MONTANT", DF.format(amount) + " E") +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                center("(scannable en caisse - solde au registre)", WIDTH) + "\n" +
                "\n";
        hardwareService.printReceipt(sb);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printChangeVoucher");
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
            case CREDIT -> "CREDIT CLIENT";
        };
    }

    // --------------------------------------------------
    // Formatting helpers
    // --------------------------------------------------

    /**
     * Returns a printable string for a value that may be absent.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Centres a text on a given width.
     *
     * <p>Delegates to the shared primitives so an administered template that
     * centres a banner produces the very same characters as this layout — the
     * whole point of having ONE implementation.
     *
     * @param text the text to centre
     * @param width the width to centre on
     * @return the padded text, without its terminator
     */
    private String center(String text, int width) {
        return new com.intermarche.pos.service.DocumentLayout(width).centre(text);
    }

    /**
     * Wraps a message onto lines no wider than the given width.
     *
     * @param text the message to wrap
     * @param width the paper width in characters
     * @return the wrapped lines, without their terminators
     */
    private List<String> wrap(String text, int width) {
        return new com.intermarche.pos.service.DocumentLayout(width).wrap(text);
    }

    /**
     * Formats a label / value pair on one receipt line, value right-aligned.
     *
     * <p>Keeps the trailing newline the forty-odd call sites of this layout
     * expect: the primitive returns a line, this helper returns a line AND its
     * terminator, because a StringBuilder assembling a document needs both.
     *
     * @param left the left label
     * @param right the right value
     * @return the formatted line, newline included
     */
    private String formatLine(String left, String right) {
        return LAYOUT.line(left, right) + "\n";
    }
    /**
     * Prints an operator's badge ticket (LC-01-06-01): the badge id in
     * clear, usable by manual entry or by the register's own scan bus (a
     * graphical barcode would need printer barcode primitives, out of this
     * text printer's reach — the number IS the credential).
     *
     * @param employee the operator whose badge is reprinted
     */
    public void printOperatorBadge(com.intermarche.pos.domain.people.Employee employee) {
        LOGGER.info("Entering method printOperatorBadge with employee: " + employee);
        String sb = logoHeader() + center("BADGE OPERATEUR", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine("OPERATEUR", employee.loginName) +
                formatLine("BADGE", employee.badgeId != null ? employee.badgeId : "-") +
                "-".repeat(WIDTH) + "\n" +
                center("SCANNEZ OU SAISISSEZ CE NUMERO", WIDTH) + "\n";
        hardwareService.printReceipt(sb);
        LOGGER.info("Exiting method printOperatorBadge");
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
    public void printParkedTicket(com.intermarche.pos.domain.sale.Ticket draft) {
        LOGGER.info("Entering method printParkedTicket with draft: " + draft);
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
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
        LOGGER.info("Exiting method printParkedTicket");
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
        LOGGER.info("Entering method printTicketIdentityBarcode with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printTicketIdentityBarcode");
            return;
        }
        String sb = center("IDENTIFIANT TICKET", WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                center(ticket.ticketNumber, WIDTH) + "\n" +
                "\n" + barcode(ticket.ticketNumber) + "\n";
        hardwareService.printReceipt(sb);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printTicketIdentityBarcode");
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
        LOGGER.info("Entering method printCardReceipt with ticketId: " + ticketId + ", signatureRequired: " + signatureRequired + ", mention: " + mention);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printCardReceipt");
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
        LOGGER.info("Exiting method printCardReceipt");
        return printed;
    }

    /**
     * Prints the slip handed over with a settlement taken on the loyalty
     * balance (BO-03-03-10).
     *
     * <p>An ADDITIONAL ticket, emitted after the sale receipt and never instead
     * of it, exactly like the card slip beside a card payment: it states one
     * settlement — what was taken, from which card, against which sale — which
     * is what a customer disputing a cagnotte débit comes back with. A sale
     * settled without the balance prints nothing, so the caller does not have to
     * know whether the cagnotte was used.
     *
     * @param ticketId the database id of the closed ticket
     * @return the number of slips printed — zero when the ticket is unknown or
     *         was settled without the loyalty balance
     */
    @Transactional
    public int printLoyaltyReceipt(Long ticketId) {
        LOGGER.info("Entering method printLoyaltyReceipt with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printLoyaltyReceipt");
            return 0;
        }
        BigDecimal used = loyaltyPaid(ticket);
        if (used.signum() <= 0) {
            LOGGER.info("Exiting method printLoyaltyReceipt");
            return 0;
        }
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.LOYALTY_RECEIPT,
                loyaltyDocumentData(ticket, used));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printLoyaltyReceipt");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("PAIEMENT FIDELITE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("Ticket : %s%n", safe(ticket.ticketNumber)));
        if (ticket.terminalId != null) {
            sb.append(String.format("Caisse : %s%n", ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format("Date   : %s%n",
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine("PAIEMENT TOTAL FID", DF.format(used) + " E"));
        sb.append(formatLine("CARTE", safe(ticket.fidelityCard)));
        if (ticket.fidelityAvailableBalance != null) {
            sb.append(formatLine("SOLDE AVANT", DF.format(ticket.fidelityAvailableBalance) + " E"));
        }
        // imfid response B: the ledger balance after the burn, read from the
        // confirmation, never recomputed by the register.
        if (ticket.fidelityBalanceAfter != null) {
            sb.append(formatLine("SOLDE APRES", DF.format(ticket.fidelityBalanceAfter) + " E"));
        }
        sb.append("\n").append(center("SIGNATURE DU CLIENT", WIDTH)).append("\n\n\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printLoyaltyReceipt");
        return 1;
    }

    /**
     * Builds the loyalty settlement slip as an administered layout sees it
     * (BO-03-03-10).
     *
     * @param ticket the settled sale
     * @param used the amount taken on the loyalty balance
     * @return the document values, every figure already formatted
     */
    java.util.Map<String, Object> loyaltyDocumentData(Ticket ticket, BigDecimal used) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", storeData(ticket));
        data.put("terminal", safe(ticket.terminalId));
        data.put("operator", ticket.cashier == null ? "" : safe(ticket.cashier.getFullName()));
        data.put("date", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", ticket.creationDate == null ? ""
                : ticket.creationDate.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of("number", safe(ticket.ticketNumber)));
        data.put("fidelity", fidelityData(ticket));
        data.put("settlement", java.util.Map.of("amount", DF.format(used)));
        return data;
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
        LOGGER.info("Entering method printWithdrawalTicket with methodLabel: " + methodLabel + ", amount: " + amount + ", lines: " + lines + ", operator: " + operator + ", terminalId: " + terminalId);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.WITHDRAWAL_TICKET,
                movementDocumentData("PRELEVEMENT", methodLabel, null, null, amount, lines,
                        operator, terminalId));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printWithdrawalTicket");
            return;
        }
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
        LOGGER.info("Exiting method printWithdrawalTicket");
    }

    /**
     * Prints the coin-supply (apport de monnaie) bordereau (EXP-15): the mirror
     * of the withdrawal slip, stating the denominations brought INTO the drawer,
     * how many of each and for how much. Built-in layout only — the apport has no
     * administered document type.
     *
     * @param amount     the total brought in
     * @param lines      the per-denomination detail, already formatted label/value
     * @param operator   the operator's name, blank when unknown
     * @param terminalId the register the apport was made on
     */
    public void printDepositTicket(BigDecimal amount, java.util.List<String[]> lines,
            String operator, String terminalId) {
        LOGGER.info("Entering method printDepositTicket with amount: " + amount + ", lines: " + lines + ", operator: " + operator + ", terminalId: " + terminalId);
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("APPORT DE MONNAIE", WIDTH)).append("\n");
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
        if (lines != null) {
            for (String[] line : lines) {
                sb.append(formatLine(line[0], line[1]));
            }
            if (!lines.isEmpty()) {
                sb.append("-".repeat(WIDTH)).append("\n");
            }
        }
        sb.append(formatLine("TOTAL APPORTE", DF.format(amount) + " E"));
        sb.append("\n");
        sb.append("Signature: ..............................\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printDepositTicket");
    }

    /**
     * Prints the standalone settlement voucher (EXP-80): a control document
     * listing the session's settlements per method with the tax-included total,
     * autonomous from the withdrawal ticket (which lists settlements only as a
     * by-product of a cash prélèvement). Built-in layout only.
     *
     * @param report the session report whose settlements are listed
     * @param operator the operator's name, blank when unknown
     */
    public void printSettlementVoucher(CashSessionService.SessionReport report, String operator) {
        LOGGER.info("Entering method printSettlementVoucher with operator: " + operator);
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("BORDEREAU DE REGLEMENT", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report != null && report.session != null) {
            sb.append(String.format("Session : %s%n", report.session.sessionNumber));
            sb.append(String.format("Caisse  : %s%n", report.session.terminalId));
        }
        sb.append(String.format("Date    : %s%n", java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format("Operateur : %s%n", operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report != null) {
            for (CashSessionService.SessionReport.MethodRow row : report.getMethodRows()) {
                sb.append(formatLine(row.method(), row.amountFormatted() + " E"));
            }
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(formatLine("TOTAL TTC", (report.getTotalIncludingTaxFormatted()) + " E"));
        }
        sb.append("\n");
        sb.append("Signature: ..............................\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printSettlementVoucher");
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
        LOGGER.info("Entering method printTransferTicket with fromLabel: " + fromLabel + ", toLabel: " + toLabel + ", amount: " + amount + ", operator: " + operator + ", terminalId: " + terminalId);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.TRANSFER_TICKET,
                movementDocumentData("TRANSFERT REGLEMENT", null, fromLabel, toLabel, amount,
                        null, operator, terminalId));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printTransferTicket");
            return;
        }
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
        LOGGER.info("Exiting method printTransferTicket");
    }

    /**
     * Prints the change-order slip (bordereau de commande de monnaie,
     * LC-12-05-02/08).
     *
     * <p>The slip carries the register number, the operator (number and name),
     * the timestamp and a table with one row per denomination and the columns
     * "dénomination", "montant" and "nbr. rouleaux". A slip printed empty
     * (LC-12-05-02) carries the denomination list only, for the shop to fill in
     * by hand; a filled slip (LC-12-05-08) carries the amounts and roll counts
     * the operator dialed, each cell filled or left blank as they chose. There
     * is no administered layout for this document — it is a built-in ticket.
     *
     * @param terminalId the register number, or null
     * @param operatorNumber the operator's number (badge), or null
     * @param operatorName the operator's name, or null
     * @param rows one {@code {dénomination, montant, nbr. rouleaux}} triple per
     *        denomination; the montant and rolls are blank on an empty slip
     */
    public void printChangeOrder(String terminalId, String operatorNumber, String operatorName,
            java.util.List<String[]> rows) {
        LOGGER.info("Entering method printChangeOrder with terminalId: " + terminalId + ", operatorNumber: " + operatorNumber);
        StringBuilder sb = new StringBuilder();
        sb.append(center("INTERMARCHE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center("COMMANDE DE MONNAIE", WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format("Caisse : %s%n", terminalId));
        }
        String operator = ((operatorNumber == null ? "" : operatorNumber) + " "
                + (operatorName == null ? "" : operatorName)).trim();
        if (!operator.isBlank()) {
            sb.append(String.format("Operateur : %s%n", operator));
        }
        sb.append(String.format("Date   : %s%n", java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("%-20s%10s%12s%n", "DENOMINATION", "MONTANT", "NBR.ROUL."));
        sb.append("-".repeat(WIDTH)).append("\n");
        if (rows != null) {
            for (String[] row : rows) {
                String label = row.length > 0 ? safe(row[0]) : "";
                String montant = row.length > 1 ? safe(row[1]) : "";
                String rolls = row.length > 2 ? safe(row[2]) : "";
                sb.append(String.format("%-20s%10s%12s%n",
                        label.length() > 20 ? label.substring(0, 20) : label, montant, rolls));
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printChangeOrder");
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
        LOGGER.info("Entering method printAbandonTicket with ticketId: " + ticketId + ", reason: " + reason + ", withDetail: " + withDetail + ", operator: " + operator);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printAbandonTicket");
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
        LOGGER.info("Exiting method printAbandonTicket");
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
        LOGGER.info("Entering method printCollectionVoucher with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printCollectionVoucher");
            return 0;
        }
        java.util.List<TicketLine> marked = new java.util.ArrayList<>();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled && line.toCollect) {
                marked.add(line);
            }
        }
        if (marked.isEmpty()) {
            LOGGER.info("Exiting method printCollectionVoucher");
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
        LOGGER.info("Exiting method printCollectionVoucher");
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
        LOGGER.info("Entering method printExchangeVoucher with ticketId: " + ticketId + ", lineIds: " + lineIds);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printExchangeVoucher");
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
        LOGGER.info("Exiting method printExchangeVoucher");
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
        LOGGER.info("Entering method printCardCreditReceipt with refund: " + refund);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CARD_RECEIPT,
                cardDocumentData("CREDIT", refund.totalAmount, null, refund.terminalId,
                        refund.creationDate == null ? null : refund.creationDate.format(
                                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printCardCreditReceipt");
            return;
        }
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
        LOGGER.info("Exiting method printCardCreditReceipt");
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
        LOGGER.info("Entering method printCardTnaReceipt with amount: " + amount + ", frame: " + frame);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CARD_RECEIPT,
                cardDocumentData("ABANDON DEBIT", amount, frame, null, null));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printCardTnaReceipt");
            return;
        }
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
        LOGGER.info("Exiting method printCardTnaReceipt");
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
