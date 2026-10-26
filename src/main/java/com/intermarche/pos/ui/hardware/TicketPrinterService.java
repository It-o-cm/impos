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

    /** LC-08-02-06: stores only explicit customer copies, never global printer output. */
    @Inject
    com.intermarche.pos.ui.customer.CustomerDocumentService customerDocumentService;

    /** LC-08-03-07: one policy for associated sale, return and deposit documents. */
    @Inject
    PrintPolicy printPolicy;

    /** Keeps the electronic customer copy even when its paper is not requested.
     * @param kind customer-only archive family
     * @param reference document reference
     * @param content rendered customer content
     * @param documentKey administered forced-document key
     * @param cut whether this paper requires an immediate cut
     * @return one when printed, zero when the policy suppresses the paper
     */
    private int printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind kind,
                                             Object reference, String content, String documentKey, boolean cut) {
        archiveCustomerDocument(kind, reference, content);
        if (printPolicy != null && !printPolicy.isAssociatedDocumentPrinted(documentKey)) {
            // LC-08-02-02: kept from the printer, generated all the same.
            hardwareService.recordUnprinted(content);
            return 0;
        }
        hardwareService.printReceipt(content);
        if (cut) {
            hardwareService.cutPaper();
        }
        return 1;
    }


    /** Freezes an issued customer copy for the customer document e-mail screen.
     * @param kind customer-only family
     * @param reference issue identifier
     * @param content exact rendered copy
     */
    public void archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind kind,
                                        Object reference, String content) {
        if (customerDocumentService != null) {
            customerDocumentService.archive(kind, String.valueOf(reference), content);
        }
    }


    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketPrinterService.class);

    /**
     * The localised labels of the paper (LC-13-06-11).
     *
     * <p>APPLICATION-SCOPED ON PURPOSE: a card receipt is laid out from the
     * payment terminal's follower thread, outside any request, and a
     * request-scoped resolver threw there.
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The back-office parameters (EAN on paper, LC-02-04-04). */
    @jakarta.inject.Inject
    PosSettingsService posSettingsService;

    /** The distributed images: receipt logo and advantage visuals (BO-03-03-15). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.StoreImageService storeImageService;

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
     * The Portuguese fiscal documents (LC-08-01-05). Null when this printer was
     * built by hand in a unit test, which simply means no document is issued here.
     */
    @Inject
    com.intermarche.pos.ui.invoice.PortugueseFiscalService portugueseFiscalService;

    /** The symbol a register prints when no settings service answers for it. */
    private static final String BUILT_IN_CURRENCY_SYMBOL = "E";

    /**
     * The register's own identifier, for the documents that name the register
     * without naming a sale (BO-03-10-05). Null on a bare instance built
     * without CDI, in which case the slip simply does not state it.
     */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

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

    /**
     * The NAME a document carries when printed, administered or the
     * register's own (BO-03-04-12).
     *
     * @param type the document being printed
     * @param builtIn the wording this register prints when none is administered
     * @return the name to print, never null
     */
    private String documentTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type, String builtIn) {
        String administered = com.intermarche.pos.domain.setting.DocumentTemplate.titleFor(type);
        return administered == null ? builtIn : administered;
    }

    /**
     * The name a document carries WHEN REPRINTED, administered or the
     * register's own (BO-03-04-13).
     *
     * <p>The rank is appended by the caller, not here: a shop renames the
     * document, it does not renumber its reprints.
     *
     * @param type the document being reprinted
     * @param builtIn the wording this register prints when none is administered
     * @return the name to print, never null
     */
    private String duplicateTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type, String builtIn) {
        String administered = com.intermarche.pos.domain.setting.DocumentTemplate.duplicateTitleFor(type);
        return administered == null ? builtIn : administered;
    }

    /**
     * Renders an administered BANNER — the head or the foot of a document —
     * as the lines it prints (BO-03-05-01 to -04, BO-03-04-02 and -08).
     *
     * <p>A banner is a document like any other to the renderer, which is the
     * point: it is a named, coded, previewed, distributed template, so a shop
     * writes as many lines as it wants, aligns each of them with
     * {@code fmt.centre}, {@code fmt.right} or plain text, and keeps more than
     * ten of them side by side under different codes. The single centred line
     * of {@code ticket.header-message} could do none of that.
     *
     * <p>It applies to the BUILT-IN document only. Where the shop administers
     * the whole layout, the head and the foot are inside that author's source,
     * and printing a banner around it would state the head twice.
     *
     * <p>A trailing empty line is dropped: a text block almost always ends on a
     * newline, and that one would feed blank paper on every ticket of the day.
     *
     * @param type the banner being printed
     * @param data the values the layout may read
     * @return the banner's lines, or null when the shop administers none
     */
    private java.util.List<String> administeredBanner(
            com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type, java.util.Map<String, Object> data) {
        // BO-03-09-02: the head or foot model CHOSEN for this register wins
        // over the first one of its type.
        com.intermarche.pos.domain.setting.DocumentTemplate chosen = chosenBanner(type);
        String rendered = chosen == null ? administeredLayout(type, data)
                : documentTemplateService.renderTemplate(chosen, data);
        if (rendered == null) {
            return null;
        }
        java.util.List<String> lines =
                new java.util.ArrayList<>(java.util.Arrays.asList(rendered.split("\\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * The head or foot model chosen for this register among the administered
     * templates (BO-03-09-02), or null when none is chosen, the code is unknown
     * or it names a template of another kind.
     *
     * @param type the banner being printed
     * @return the chosen template, or null for the first one of its type
     */
    com.intermarche.pos.domain.setting.DocumentTemplate chosenBanner(
            com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType type) {
        if (posSettingsService == null || documentTemplateService == null) {
            return null;
        }
        String code = type == com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.TICKET_HEADER
                ? posSettingsService.ticketHeaderTemplate() : posSettingsService.ticketFooterTemplate();
        com.intermarche.pos.domain.setting.DocumentTemplate chosen =
                com.intermarche.pos.domain.setting.DocumentTemplate.findByCode(code);
        return chosen == null || chosen.documentType != type ? null : chosen;
    }

    /**
     * Renders the VAT table of a sale as a coupon of its own, printed after the
     * ticket when the register's model is SEPARATE (BO-03-09-03).
     *
     * @param ticket the sale
     * @return the coupon as printable text
     */
    public String renderVatCoupon(Ticket ticket) {
        LOGGER.info("Entering method renderVatCoupon");
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled) {
                breakdown.add(line.vatRate, line.vatNumber, line.totalPrice, line.vatExempt, line.thirdPartySale);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.both("paper.detailTva"), WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket"), ticket.ticketNumber));
        sb.append("-".repeat(WIDTH)).append("\n");
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            sb.append(vatVentilationLine(ventilationLabel(bucket), bucket));
        }
        sb.append(formatLine(i18n.both("paper.dontTva"), money(DF.format(ticket.totalVat))));
        LOGGER.info("Exiting method renderVatCoupon");
        return sb.toString();
    }

    /** French display format for amounts on the receipt. */
    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** Standard 80mm receipt width, in characters. */
    private static final int WIDTH = 42;

    /** Advantage lines grouped by advantage type — the default (BO-03-03-25). */
    private static final String GROUPING_TYPE = "TYPE";

    /** Advantage lines grouped by advantage category (BO-03-03-33). */
    private static final String GROUPING_CATEGORY = "CATEGORY";

    /** Advantage lines printed as a flat list, as they were received. */
    private static final String GROUPING_NONE = "NONE";

    /**
     * Names a settlement key of a session report (X, Z, settlement voucher) with
     * the very word the customer's receipt prints for that tender.
     *
     * <p>The paper used to print the bare key — "CASH", "CARD" — under the
     * settlements of a Z the cashier signs, while the receipt of the same sale
     * read "ESPECES" and "CARTE BANCAIRE". A key no payment type carries (a
     * tender the store added) prints itself, as data would.
     *
     * @param key the settlement key
     * @return the paper label of that key
     */
    private String methodLabel(String key) {
        Class<?> type = com.intermarche.pos.domain.payment.PaymentTypes.forKey(key);
        return type == null ? key : typeLabel(type.getSimpleName().replace("Payment", ""));
    }

    /**
     * Names a settlement method as the CUSTOMER reads it on the paper.
     *
     * <p>IT WAS THE JAVA CLASS NAME, upper-cased. {@code CashPayment} printed
     * "CASH" on a French receipt, {@code ChequePayment} printed "CHEQUE" by pure
     * luck, and {@code SupplierGiftCardPayment} would have printed
     * "SUPPLIERGIFTCARD". A label the customer reads is not a class name with its
     * suffix chopped off: it is a word the shop chose, in the language the
     * register is set to, and {@code LC-07-03-06} names it "ESPECES".
     *
     * <p>The switch reads the SIMPLE TYPE NAME rather than the discriminator key
     * of {@link com.intermarche.pos.domain.payment.PaymentTypes}, to stay the
     * same shape as the A4 invoice's own tender naming — each document keeps its
     * own register of words, the paper in capitals without accents because that
     * is what a thermal head prints, the invoice in sentence case.
     *
     * <p>A method this version has no word for falls back to the bare type name,
     * which is ugly and visible — far better than a blank where an amount is
     * justified, and it names exactly what has to be added.
     *
     * @param payment the settled payment
     * @return the label, already rendered in both customer languages
     */
    private String tenderLabel(TicketPayment payment) {
        // BO-03-02-01: a settlement taken as a tender the store added prints
        // that tender's own label.
        com.intermarche.pos.domain.payment.TenderDefinition added = payment.tenderCode == null
                ? null : com.intermarche.pos.domain.payment.TenderDefinition.findByCode(payment.tenderCode);
        if (added != null && added.label != null && !added.label.isBlank()) {
            return added.label;
        }
        // A voucher names itself on the paper as it did on the payment screen
        // and the customer display: a self-scanning REDUCTION voucher is not a
        // purchase voucher, and the generic word only stands for one that
        // carries no label of its own.
        if (payment instanceof com.intermarche.pos.domain.payment.VoucherPayment voucher
                && voucher.voucherLabel != null && !voucher.voucherLabel.isBlank()) {
            return voucher.voucherLabel.trim();
        }
        return typeLabel(payment.getClass().getSimpleName().replace("Payment", ""));
    }

    /**
     * Names a payment type, by its simple name without the {@code Payment}
     * suffix, in the paper's register of words.
     *
     * @param type the simple type name, e.g. {@code Cash}
     * @return the label, already rendered in both customer languages
     */
    private String typeLabel(String type) {
        return switch (type) {
            case "Cash" -> i18n.both("paper.especes");
            case "Card" -> i18n.both("paper.carteBancaire");
            case "Cheque" -> i18n.both("paper.cheque");
            case "DeferredCheque" -> i18n.both("pay.deferredCheque");
            case "Voucher" -> i18n.both("paper.bonAchat");
            case "Fidelity" -> i18n.both("paper.cagnotte2");
            case "TicketResto" -> i18n.both("paper.ticketResto");
            case "Credit" -> i18n.both("paper.creditClient");
            case "Backup" -> i18n.both("paper.secoursMonetique");
            case "TrdCard" -> i18n.both("paper.carteTrd");
            case "SocialCard" -> i18n.both("paper.carteSociale");
            case "RestoFlash" -> i18n.both("paper.restoFlash");
            case "SupplierGiftCard" -> i18n.both("paper.carteCadeau");
            case "Sogec" -> i18n.both("paper.bonSogec");
            // LC-06-03-07: the settlement of a recovered deposit names itself,
            // like every other tender of the receipt.
            case "Deposit" -> i18n.both("paper.repriseAcompte");
            default -> type.toUpperCase();
        };
    }

    /**
     * The typesetting primitives of this register's paper, shared with the
     * template renderer so a factory template lays out exactly as this class
     * does rather than re-implementing the alignment.
     */
    private static final com.intermarche.pos.service.DocumentLayout LAYOUT =
            new com.intermarche.pos.service.DocumentLayout(WIDTH);

    /**
     * Prints the SLIP EACH TENDER OF A TICKET CARRIES, if any
     * (BO-03-02-26, -31, -32 and -33).
     *
     * <p>A tender names a gabarit, says how many times it is printed, whether
     * a duplicate goes with it, and whether it is printed even when the
     * receipt is not. Four administered answers, and nothing in Java deciding
     * which tender has a slip: a shop that attaches a layout to its gift card
     * gets one, a shop that attaches none gets nothing, and neither is a
     * release.
     *
     * <p>CALLED AFTER THE RECEIPT, not instead of it. The slip is a proof of
     * SETTLEMENT and the receipt is the proof of SALE; a customer who took a
     * digital receipt still leaves with the paper of their cheque when the
     * shop administered it forced.
     *
     * <p>Each tender of the ticket is served once even when it settled twice:
     * two cash lines are one way of paying, and printing the slip twice would
     * be the questionnaire's print count said by accident.
     *
     * @param ticket the settled ticket
     * @param receiptPrinted whether the sale receipt was printed
     * @return how many slips were sent to the printer
     */
    public int printTenderSlips(Ticket ticket, boolean receiptPrinted) {
        LOGGER.info("Entering method printTenderSlips with receiptPrinted: " + receiptPrinted);
        if (ticket == null || ticket.payments == null) {
            LOGGER.info("Exiting method printTenderSlips");
            return 0;
        }
        int printed = 0;
        java.util.Set<String> served = new java.util.HashSet<>();
        for (com.intermarche.pos.domain.payment.TicketPayment payment : ticket.payments) {
            // BO-03-02-01: a tender the store added prints ITS slip.
            String key = payment.tenderCode != null ? payment.tenderCode : payment.getMethodKey();
            if (key == null || !served.add(key)) {
                continue;
            }
            printed += printOneTenderSlip(ticket, key, receiptPrinted);
        }
        LOGGER.info("Exiting method printTenderSlips");
        return printed;
    }

    /**
     * Prints the slip of ONE tender, honouring its four administered answers.
     *
     * @param ticket the settled ticket, handed to the layout
     * @param tenderCode the tender that settled
     * @param receiptPrinted whether the sale receipt was printed
     * @return how many slips were sent to the printer for that tender
     */
    private int printOneTenderSlip(Ticket ticket, String tenderCode, boolean receiptPrinted) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                com.intermarche.pos.domain.payment.TenderDefinition.findByCode(tenderCode);
        if (tender == null || tender.documentTemplateCode == null
                || tender.documentTemplateCode.isBlank()) {
            return 0;
        }
        if (!receiptPrinted && !tender.printForced) {
            // The shop did not ask for this slip to outlive the receipt.
            return 0;
        }
        com.intermarche.pos.domain.setting.DocumentTemplate template = com.intermarche.pos.domain.setting.DocumentTemplate.findByCode(tender.documentTemplateCode);
        if (template == null || !template.isRenderable() || documentTemplateService == null) {
            // A tender naming a gabarit that has not arrived prints nothing
            // rather than failing the settlement.
            return 0;
        }
        String body = documentTemplateService.renderTemplate(template,
                saleDocumentData(ticket, false, 0));
        if (body == null) {
            return 0;
        }
        int copies = Math.max(1, tender.printCount);
        for (int copy = 0; copy < copies; copy++) {
            hardwareService.printReceipt(body);
            hardwareService.cutPaper();
        }
        if (!tender.printDuplicate) {
            return copies;
        }
        String duplicate = documentTemplateService.renderTemplate(template,
                saleDocumentData(ticket, true, 1));
        if (duplicate == null) {
            return copies;
        }
        hardwareService.printReceipt(duplicate);
        hardwareService.cutPaper();
        return copies + 1;
    }

    /**
     * Loads a ticket from the database and prints it. The first print is the
     * original; any further print is marked "DUPLICATA N°x", counted on the
     * ticket and recorded in the journal.
     *
     * @param ticketId the database id of the ticket to print
     */
    @Transactional
    public void printTicket(Long ticketId) {
        printTicket(ticketId, true);
    }

    /** Prints a sale while allowing the WCL customer frame to remain attached (LC-07-04-11).
     * @param ticketId persisted sale identifier
     * @param cut whether to cut immediately after the sale
     */
    @Transactional
    public void printTicket(Long ticketId, boolean cut) {
        LOGGER.info("Entering method printTicket with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket introuvable pour impression : " + ticketId);
        }

        boolean duplicata = ticket.printCount >= 1;
        int duplicataNumber = ticket.printCount; // 1st reprint = duplicata n°1

        // LC-08-01-05: the sale under way is stated on its Portuguese documents
        // before its first paper, so the paper carries them.
        if (portugueseFiscalService != null) {
            portugueseFiscalService.issueSaleDocuments(ticketId);
        }
        java.util.List<com.intermarche.pos.domain.sale.FiscalDocument> fiscal =
                com.intermarche.pos.domain.sale.FiscalDocument.activeForTicket(ticket.id);
        String content = renderTicket(ticket, duplicata, duplicataNumber);

        // Count the print and journal the duplicata before sending to the printer
        ticket.printCount++;
        ticket.persist();
        if (duplicata) {
            technicalEventService.log(TechnicalEvent.EventType.DUPLICATA_PRINTED,
                    ticket.ticketNumber + " n°" + duplicataNumber);
        }

        // LC-08-01-06: a sale stated on two documents prints two papers, each
        // cut from the next.
        if (fiscal.size() > 1) {
            for (int part = 0; part < fiscal.size(); part++) {
                hardwareService.printReceipt(renderFiscalDocument(fiscal.get(part), duplicata, duplicataNumber));
                if (cut || part < fiscal.size() - 1) {
                    hardwareService.cutPaper();
                }
            }
            LOGGER.info("Exiting method printTicket");
            return;
        }
        // Send to the printer
        hardwareService.printReceipt(content);
        // BO-03-09-03: the SEPARATE model prints the VAT table on its own coupon.
        if ("SEPARATE".equals(posSettingsService.vatTableMode())) {
            hardwareService.cutPaper();
            hardwareService.printReceipt(renderVatCoupon(ticket));
        }
        if (cut) {
            hardwareService.cutPaper();
        }
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
        // LC-08-01-06: a sale stated on two Portuguese documents is those two
        // documents, one after the other.
        java.util.List<com.intermarche.pos.domain.sale.FiscalDocument> fiscal =
                com.intermarche.pos.domain.sale.FiscalDocument.activeForTicket(ticket.id);
        if (fiscal.size() > 1) {
            StringBuilder parts = new StringBuilder();
            for (com.intermarche.pos.domain.sale.FiscalDocument part : fiscal) {
                parts.append(renderFiscalDocument(part, duplicata, duplicataNumber));
            }
            LOGGER.info("Exiting method renderTicket");
            return parts.toString();
        }
        com.intermarche.pos.domain.sale.FiscalDocument document = fiscal.isEmpty() ? null : fiscal.get(0);
        // BO-03-03: the store's own layout first. A shop that administers none —
        // or one whose layout does not hold together — gets the receipt below,
        // character for character as before.
        java.util.Map<String, Object> documentData =
                saleDocumentData(ticket, duplicata, duplicataNumber);
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT, documentData);
        if (administered != null) {
            LOGGER.info("Exiting method renderTicket");
            String value = ticketBarcode(ticket.ticketNumber);
            String clean = administered.replace(barcode(value), "").replace(qrCode(value), "").stripTrailing();
            // LC-08-04-23: an administered layout still states its document.
            String legal = document == null ? "" : "\n" + fiscalHeader(document) + fiscalFooter(document);
            return clean + legal + saleIdentificationCode(ticket.ticketNumber);
        }
        StringBuilder sb = new StringBuilder();
        // Header. The logo prints above the store name when this till has one; the
        // text line stays regardless, so a receipt is readable even on a printer that
        // ignores images or a deployment with no logo file.
        sb.append(logoHeader());
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append(center(ticket.store.name, WIDTH)).append("\n");
        sb.append(center(ticket.store.address.city, WIDTH)).append("\n");
        // BO-03-05-01: the administered HEAD, printed under the store address.
        // A shop that writes one gets its lines, aligned as it aligned them; a
        // shop that writes none keeps the single centred message of
        // BO-03-08-03, which is what this register printed before.
        java.util.List<String> headBanner = administeredBanner(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.TICKET_HEADER, documentData);
        if (headBanner != null) {
            for (String bannerLine : headBanner) {
                sb.append(fillBannerLine(bannerLine, storeData(ticket))).append("\n");
            }
        } else {
            String headerMessage = posSettingsService.ticketHeaderMessage();
            if (headerMessage != null && !headerMessage.isBlank()) {
                sb.append(center(fillStorePlaceholders(headerMessage, storeData(ticket)), WIDTH)).append("\n");
            }
        }
        if (duplicata) {
            sb.append("-".repeat(WIDTH)).append("\n");
            // BO-03-04-13: the reprint carries the administered name when the
            // shop gave it one, the register's own otherwise; the rank is the
            // register's business either way.
            sb.append(center(String.format(
                    duplicateTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.SALE_RECEIPT,
                            i18n.get("paper.duplicata")),
                    duplicataNumber), WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Ticket info
        sb.append(String.format(i18n.get("paper.ticket"), ticket.ticketNumber));
        sb.append(String.format(i18n.get("paper.date"), ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(String.format(i18n.get("paper.vendeur"), ticket.cashier.getFullName()));
        // LC-08-06-11: a sale stated on a Portuguese document prints the NIF in the
        // document's own block, country code included.
        boolean taxIdPrinted = document == null
                && ticket.customerTaxId != null && !ticket.customerTaxId.isBlank();
        if (taxIdPrinted) {
            sb.append(i18n.both("doc.nif")).append(saleTaxIdLabel(ticket)).append("\n");
        }
        // LC-08-01-05 / LC-08-04-18: the Portuguese document the sale is stated on.
        if (document != null) {
            sb.append(fiscalHeader(document));
        }
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
        // BO-03-03-17 / BO-03-03-18: the two rules laid over that order — the
        // family headings' own order, and the article attribute the lines are
        // sorted or isolated on.
        boolean familyAlphabetical = posSettingsService.ticketFamilyAlphabetical();
        String lineAttribute = posSettingsService.ticketLineAttribute();
        String lineIsolation = posSettingsService.ticketLineAttributeIsolate();
        // EXP-43: the running sum since the previous SOUS-TOTAL marker, printed at
        // each boundary so each shopper of a shared basket reads their part.
        java.math.BigDecimal subtotalRunning = java.math.BigDecimal.ZERO;
        // LC-05-07-02: on a ticket mixing sales and returns, where the store asks
        // for it, the returns are detailed on a ticket of their own and this one
        // carries their value only.
        boolean separateReturns = posSettingsService.returnSeparateDetail() && mixed(ticket);
        java.math.BigDecimal returnsValue = java.math.BigDecimal.ZERO;
        // BO-10-08-30: the cancelled lines, printed marked and outside the
        // totals where the shop asks for it. BO-10-07-15: the subtotal of each
        // family and the bold amounts. BO-10-08-31: identical articles one per
        // line where the shop does not want them grouped. BO-10-06-05/-06:
        // the eco-taxes the articles carry, each with its print choice.
        boolean cancelledPrinted = posSettingsService.cancelledLinesPrinted();
        boolean familySubtotals = groupByFamily && posSettingsService.ticketFamilySubtotals();
        boolean boldAmounts = posSettingsService.ticketBoldAmounts();
        java.util.List<com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition> ecoTaxes =
                com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition.orBuiltIn(
                        posSettingsService.ecoTaxes());
        com.intermarche.pos.domain.catalog.UnitFormat units =
                com.intermarche.pos.domain.catalog.UnitFormat.orDefault(posSettingsService.unitFormat());
        java.math.BigDecimal familyRunning = java.math.BigDecimal.ZERO;
        // BO-03-03-17: the nomenclature level the family headings are read at.
        int familyLevel = posSettingsService.ticketFamilyLevel();
        // BO-03-03-23: where the engine's promotion lines are printed.
        String promotionPlacement = posSettingsService.ticketPromotionPlacement();
        java.util.List<TicketLine> printedLines = TicketLineOrder.apply(ticket.lines, lineOrder,
                familyAlphabetical, lineAttribute, lineIsolation, familyLevel);
        if (posSettingsService.identicalLinesDetailed()) {
            printedLines = detailIdentical(printedLines, valuations);
        }
        for (TicketLine line : printedLines) {
            // A cancelled article (lot C4, BO-04-01-16) is kept in the ticket
            // as a journal witness; it prints only where the shop asks for it,
            // and never counts in a total.
            if (line.cancelled && !cancelledPrinted) continue;
            if (!line.cancelled) {
                subtotalRunning = subtotalRunning.add(line.totalPrice);
                if (separateReturns && line.returnLine) {
                    returnsValue = returnsValue.add(line.totalPrice);
                    continue;
                }
            }
            if (groupByFamily) {
                String family = TicketLineOrder.familyAt(line, familyLevel);
                if (!family.equals(printedFamily)) {
                    if (familySubtotals && printedFamily != null) {
                        sb.append(amountLine(i18n.both("paper.sousTotal"),
                                money(DF.format(familyRunning)), boldAmounts));
                        familyRunning = java.math.BigDecimal.ZERO;
                    }
                    sb.append(center(family, WIDTH)).append("\n");
                    printedFamily = family;
                }
            }
            if (line.cancelled) {
                sb.append(cancelledLine(line));
                continue;
            }
            familyRunning = familyRunning.add(line.totalPrice);
            // Product label (possibly truncated)
            String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
            // P3-5 / LC-02-05-04 / LC-02-13-18: the label, then ONE decomposition
            // sub-line "quantité × prix unitaire" beneath it — the very line the two
            // screens show, built by the shared formatter (euro glyph "E" on paper).
            // Covers the four cases (piece, measure unit, weighed, price-embedded
            // deduced weight); empty — so absent — for a single unit sold by the piece.
            // The label stands ALONE on its line, so it is NOT padded: the
            // %-20s it inherited from the old "label + quantity x price" format
            // left the old column width behind as trailing blanks on a printed
            // line, and a receipt line that ends in spaces is a receipt line
            // that differs from itself once trimmed.
            sb.append(label).append("\n");
            String detail = units.detailLine(
                    line.quantity, line.unitPrice, line.unitName,
                    line.plu != null && !line.plu.isEmpty(),
                    line.embeddedWeight, line.embeddedUnitPrice, "E");
            if (!detail.isBlank()) {
                sb.append("  ").append(detail).append("\n");
            }
            // LC-06-02-02 / -12: a line recalled from a document stating both
            // quantities prints the ordered one beside the delivered one it sells.
            if (line.orderedQuantity != null) {
                sb.append("  ").append(i18n.format("paper.qteCommandeeLivree",
                        line.orderedQuantity.stripTrailingZeros().toPlainString().replace('.', ','),
                        line.quantity.stripTrailingZeros().toPlainString().replace('.', ','))).append("\n");
            }
            // LC-02-04-04 / BO-10-02-34: the article code printed under the
            // label where the article's own flag says so, the shop's parameter
            // standing in for an article without one (or without a product).
            if (com.intermarche.pos.domain.catalog.attribute.ProductAttributes.codeShown(
                    line.product, posSettingsService.showEan())
                    && line.ean != null && !line.ean.isEmpty()) {
                sb.append("  ").append(line.ean).append("\n");
            }
            // BO-10-06-06: the article's eco-participation under its line, where
            // the shop prints it line by line. An article that carries none
            // prints nothing rather than a zero nobody owes.
            if ("LINE".equalsIgnoreCase(safe(posSettingsService.ecoTaxPrint()).trim())) {
                for (com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition ecoTax : ecoTaxes) {
                    BigDecimal lineEcoTax = ecoTaxOf(line, ecoTax);
                    if (ecoTax.printed() && lineEcoTax.signum() > 0) {
                        sb.append(formatLine("  " + ecoTaxLabel(ecoTax), money(DF.format(lineEcoTax))));
                    }
                }
            }
            // BO-10-05-04: the tare the scale deducted, under the weighed line
            // and in kilograms. Only where one was applied: a line sold by the
            // piece has no tare to state, and a zero tare is not a deduction.
            if (posSettingsService.tarePrinted() && line.appliedTareKg != null
                    && line.appliedTareKg.signum() > 0) {
                sb.append("  ").append(i18n.both("paper.tare")).append(" ")
                        .append(units.weight(line.appliedTareKg)).append("\n");
            }
            // BO-10-02-20: the SECOND code rattached to the EAN, under the same
            // label and on the same rule — the dematerialised articles are the
            // ones whose customer comes back with the paper in hand. An article
            // without one prints nothing rather than an empty indented line.
            if (posSettingsService.secondCodePrinted()
                    && line.secondCode != null && !line.secondCode.isBlank()) {
                sb.append("  ").append(line.secondCode).append("\n");
            }
            // BO-03-03-37: the NUMBER of the VAT regime, beside the article it
            // applies to, when the store asks for it — activable in France,
            // deactivable in Belgium. It is resolved from the rate the line
            // SNAPSHOTTED at scan time, so a ticket reprinted two years later
            // states the code of its day; a rate no regime carries prints
            // nothing rather than a number nobody can check.
            if (posSettingsService.ticketVatCode()) {
                String vatCode = vatCodeOf(line);
                if (vatCode != null) {
                    sb.append("  ").append(String.format(i18n.get("paper.codeTva"), vatCode));
                }
            }
            // Line total (right-aligned)
            String lineTotal = DF.format(line.totalPrice);
            sb.append(boldAmounts ? amountLine("", lineTotal, true)
                    : String.format("%" + WIDTH + "s%n", lineTotal));
            // BO-03-03-22: the line's own discount, rebate or forced price,
            // printed UNDER the article it changed when the shop asks for it.
            // The label is the one the register showed on screen, so paper and
            // screen say the same thing; the amount beside it is what the
            // modification took off THIS line, which is the figure a customer
            // checks. A forced price whose original the back office masks
            // (BO-10-07-12) carries no label and prints nothing — the register
            // states only what it vouched for on screen.
            if (posSettingsService.ticketLineDiscountPrint()
                    && line.modifierLabel != null && !line.modifierLabel.isBlank()) {
                String modifier = line.modifierLabel.length() > 26
                        ? line.modifierLabel.substring(0, 26) : line.modifierLabel;
                BigDecimal saved = lineDiscount(line);
                sb.append(formatLine("  " + modifier,
                        saved.signum() == 0 ? "" : "-" + money(DF.format(saved))));
            }
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
                            : (valuation.offerLabel != null ? valuation.offerLabel : i18n.both("paper.avantage"));
                    if (advLabel.length() > 26) advLabel = advLabel.substring(0, 26);
                    // LINE places it under the article; FOOTER — or a null
                    // placement, the default — defers it to the totals block.
                    // BO-03-03-23: the shop may impose either placement.
                    if (advantageUnderLine(promotionPlacement, valuation.advantagePrintMode)) {
                        sb.append(formatLine("  " + advLabel, money(DF.format(delta))));
                    } else {
                        footerAdvantages.append(formatLine(advLabel, money(DF.format(delta))));
                    }
                }
                if (correction.signum() > 0) {
                    String corLabel = valuation.correctionLabel != null
                            ? valuation.correctionLabel : i18n.both("paper.ajustement");
                    if (corLabel.length() > 26) corLabel = corLabel.substring(0, 26);
                    sb.append(formatLine("  " + corLabel, "+" + money(DF.format(correction))));
                }
            }
            // EXP-43: the SOUS-TOTAL drawn after this line, summing the lines since
            // the previous marker. A ticket with no marker prints exactly as before.
            if (line.subtotalBoundary) {
                sb.append(formatLine(i18n.both("paper.sousTotal"), money(DF.format(subtotalRunning))));
                subtotalRunning = java.math.BigDecimal.ZERO;
            }
        }
        if (familySubtotals && printedFamily != null) {
            sb.append(amountLine(i18n.both("paper.sousTotal"), money(DF.format(familyRunning)), boldAmounts));
        }
        if (separateReturns) {
            sb.append(formatLine(i18n.both("paper.valeurRetours"), money(DF.format(returnsValue))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Totals
        // Footer-placed engine advantages (GB-01-07-10), each under its own
        // label, before the ticket-level discount and the grand total. Empty —
        // no advantage, or all of them placed under their line — prints nothing.
        sb.append(footerAdvantages);
        if (ticket.globalDiscountApplied != null) {
            sb.append(formatLine(i18n.both("paper.remiseTicket"),
                    "-" + money(DF.format(ticket.globalDiscountApplied))));
        }
        // BO-03-03-24: everything the customer saved at once — the line-level
        // modifications plus the ticket discount — on one line under the
        // totals. A basket that saved nothing prints nothing: a line stating
        // "0,00" of savings says nothing and costs paper.
        if (posSettingsService.ticketImmediateDiscountTotal()) {
            BigDecimal immediate = immediateDiscountTotal(ticket);
            if (immediate.signum() > 0) {
                sb.append(formatLine(i18n.both("paper.totalRemisesImmediates"),
                        "-" + money(DF.format(immediate))));
            }
        }
        // BO-10-07-10: the number of articles, where the shop asks for it —
        // beside the amount to pay, or on a line of its own under it. Read from
        // the CLOSED ticket's own count, so a duplicata restates the figure the
        // customer was shown.
        String itemCountPlacement = safe(posSettingsService.ticketItemCountPrint()).trim();
        String totalLabel = i18n.both("paper.totalTtc");
        if ("TOTAL_LINE".equalsIgnoreCase(itemCountPlacement)) {
            totalLabel = totalLabel + " (" + ticket.itemCount + ")";
        }
        // BO-10-07-14: the amount due in enlarged characters where the shop
        // asks for it. Aligned first, wrapped afterwards — the markers are not
        // characters of the line.
        String totalLine = amountLine(totalLabel, money(DF.format(ticket.totalIncludingTax)), boldAmounts);
        sb.append(posSettingsService.dueAmountLarge() ? large(totalLine) : totalLine);
        if ("OWN_LINE".equalsIgnoreCase(itemCountPlacement)) {
            sb.append(formatLine(i18n.both("paper.nombreArticles"),
                    String.valueOf(ticket.itemCount)));
        }
        // BO-11-01-20: the vignettes the sale earns under the administered formula.
        long vignettes = posSettingsService.vignetteEnabled()
                ? com.intermarche.pos.ui.cash.VignetteService.vignettesFor(ticket.totalIncludingTax,
                        posSettingsService.vignetteAmountStep(), posSettingsService.vignettePerStep())
                : 0L;
        if (vignettes > 0) {
            sb.append(formatLine(i18n.both("paper.t21Vignettes"), String.valueOf(vignettes)));
        }
        // Loyalty section (imfid lot 3): the DISPLAYED projection and its rule
        // labels — the only rule data that travels to the POS (spec §3),
        // indicative by doctrine (the ingestion's recomputation prevails).
        // It is read from the TICKET, where the closing froze it, so a
        // duplicata — here or on the store node — restates the very figures the
        // customer was shown rather than the projection of whatever sale the
        // register happens to be on.
        // BO-03-03-32: the card this sale was attached to, named in the card
        // advantage zone. Printed whatever the earn and before it: a customer
        // who presented a card and earned nothing still reads WHICH card was
        // read, which is what a disputed attachment comes back on.
        // BO-10-03-05 / -11 / -12 / -13 / -21: when the store named a gabarit
        // for this sale's case — card or no card, programme answering or not —
        // that gabarit IS the loyalty zone, and every built-in block below
        // stands down. Named none, nothing changes: the register prints what it
        // has always printed.
        String loyaltyZone = administeredLoyaltyZone(ticket, documentData);
        if (loyaltyZone != null) {
            sb.append(loyaltyZone);
            if (!loyaltyZone.endsWith("\n")) {
                sb.append("\n");
            }
        }
        if (loyaltyZone == null
                && posSettingsService.fidelityPrintCardNumber()
                && ticket.fidelityCard != null && !ticket.fidelityCard.isBlank()) {
            sb.append(formatLine(i18n.both("paper.carteFidelite"), ticket.fidelityCard));
        }
        // BO-03-03-26: the programme's short advantage message, in the card
        // advantage zone and BEFORE the advantages of the transaction.
        com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages loyaltyMessages =
                com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages.decode(ticket.fidelityMessages);
        if (loyaltyZone == null && posSettingsService.fidelityShortMessagePrinted()) {
            sb.append(shortMessageLines(loyaltyMessages));
        }
        // BO-10-03-15: the advantage section is printed only when the back
        // office activated the fidelity advantages; disabled, no section.
        if (loyaltyZone == null
                && posSettingsService.fidelityAdvantagesEnabled()
                && ticket.fidelityEarnTotal != null
                && ticket.fidelityEarnTotal.signum() > 0) {
            if (ticket.fidelityAnonymousProjection) {
                // imfid response C: a card-less ticket shows what a card would
                // have earned. No balances, no "cagnotte du jour".
                for (String messageLine
                        : wrap(i18n.both("paper.avecCarteVousAuriez"), WIDTH)) {
                    sb.append(messageLine).append("\n");
                }
                sb.append(formatLine(i18n.both("paper.cagnotte"),
                        "+" + money(DF.format(ticket.fidelityEarnTotal))));
            } else {
                sb.append(formatLine(i18n.both("paper.cagnotteJour"),
                        "+" + money(DF.format(ticket.fidelityEarnTotal))));
                appendFidelityAdvantages(sb, ticket);
                // imfid response B: the forecast balance after the sale, under an
                // administered label, marked PREVISIONNEL — nothing is credited
                // before the ingestion.
                if (ticket.fidelityProjectedAfterEarn != null) {
                    sb.append(formatLine(posSettingsService.fidelityProjectedLabel(),
                            money(DF.format(ticket.fidelityProjectedAfterEarn))));
                    sb.append(center(i18n.both("paper.previsionnel"), WIDTH)).append("\n");
                }
            }
        }
        // BO-03-03-31 / BO-03-03-34: the card's balance zone, at the foot of
        // the loyalty section. Only for a presented card, and never for an
        // anonymous projection: that one has no balance to state, only what a
        // card WOULD have earned.
        if (loyaltyZone == null
                && posSettingsService.fidelityPrintBalances()
                && ticket.fidelityCard != null && !ticket.fidelityCard.isBlank()
                && !ticket.fidelityAnonymousProjection) {
            appendFidelityBalances(sb, ticket);
        }
        // BO-10-02-33: the balance of each pot as the programme computed it
        // with this sale, under the card balances. Read from what the closing
        // froze, so a duplicata states the same figures; nothing frozen,
        // nothing printed.
        if (loyaltyZone == null) {
            appendPotBalances(sb, ticket);
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
        if (loyaltyZone == null && !fidelityMessage.isEmpty()) {
            for (String messageLine : wrap(fidelityMessage, WIDTH)) {
                sb.append(messageLine).append("\n");
            }
        }
        // BO-03-03-28: the shop's word to a customer who presented no card,
        // when the programme DID answer — distinct from the offline message
        // above, which answers for a programme that could not be asked. Said
        // once: a ticket already carrying the offline text says nothing more.
        if (loyaltyZone == null && fidelityMessage.isEmpty()
                && (ticket.fidelityCard == null || ticket.fidelityCard.isBlank())) {
            String nonHolder = posSettingsService.fidelityNonHolderMessage();
            if (nonHolder != null && !nonHolder.isBlank()) {
                for (String messageLine : wrap(nonHolder, WIDTH)) {
                    sb.append(messageLine).append("\n");
                }
            }
        }
        // BO-03-03-27: the programme's commercial messages, at the END of the
        // loyalty section, as many as the shop lets through.
        if (loyaltyZone == null) {
            sb.append(commercialMessageLines(loyaltyMessages,
                    posSettingsService.fidelityCommercialMessagesMax()));
        }
        // BO-03-09-03: the VAT table model of this register — none at all, the
        // amount alone, the amount with the table, or the amount here and the
        // table on a coupon of its own printed after the ticket.
        String vatTable = posSettingsService.vatTableMode();
        if (!"NONE".equals(vatTable)) {
            sb.append(formatLine(i18n.both("paper.dontTva"), money(DF.format(ticket.totalVat))));
        }
        // Per-rate VAT ventilation (same rule as the persisted totals)
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        for (TicketLine line : ticket.lines) {
            // Cancelled articles (lot C4) are outside the sale: excluded from
            // the per-rate VAT ventilation exactly as from the totals.
            if (line.cancelled) continue;
            // BO-02-02-01, BO-02-03-26/42: each line under its regime, an
            // exempt line and a line sold for a third party under their own
            // headings.
            breakdown.add(line.vatRate, line.vatNumber, line.totalPrice, line.vatExempt, line.thirdPartySale);
        }
        // BO-10-06-04: the per-rate VAT ventilation is printed only when the
        // back office asks for it; disabled, the ticket carries no breakdown.
        if ("COMBINED".equals(vatTable)) {
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                sb.append(vatVentilationLine(ventilationLabel(bucket), bucket));
            }
        }
        // LC-06-03-08: the ticket of a sale that settles an order states, next
        // to its own total VAT printed above, the VAT already collected on the
        // deposit — a DISTINCT statement, ventilation by ventilation, so the
        // customer and the accounts read what the deposit settled apart from
        // what this ticket carries. Printed from the detail carried by the
        // deposit recovery, never recomputed: the ventilation was fixed when
        // the deposit was taken. Nothing when the sale recovers no deposit.
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.pos.domain.payment.DepositPayment acompte
                    && acompte.vatDetail != null && !acompte.vatDetail.isBlank()) {
                java.util.List<com.intermarche.pos.domain.payment.DepositVat.Row> depositRows =
                        com.intermarche.pos.domain.payment.DepositVat.rows(acompte.vatDetail);
                if (!depositRows.isEmpty()) {
                    sb.append("-".repeat(WIDTH)).append("\n");
                    sb.append(center(i18n.both("paper.tvaAcompteDejaPercue"), WIDTH)).append("\n");
                    for (com.intermarche.pos.domain.payment.DepositVat.Row row : depositRows) {
                        sb.append(formatLine(row.getRateFormatted(), money(row.getAmountFormatted()))).append("\n");
                    }
                }
                break;
            }
        }
        // BO-02-02-02: the legal mentions the regimes of this sale oblige,
        // printed under the ventilation they belong to. Nothing administered,
        // nothing printed.
        sb.append(vatLegalMentions(breakdown));
        // BO-10-06-06: the eco-participation of the whole sale, where the shop
        // prints one line rather than one per article. Printed under the VAT
        // ventilation, which is where a customer looks for what the price
        // includes; nothing at all when the sale carries none.
        if ("TOTAL".equalsIgnoreCase(safe(posSettingsService.ecoTaxPrint()).trim())) {
            for (com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition ecoTax : ecoTaxes) {
                BigDecimal saleEcoTax = BigDecimal.ZERO;
                for (TicketLine line : ticket.lines) {
                    if (!line.cancelled) {
                        saleEcoTax = saleEcoTax.add(ecoTaxOf(line, ecoTax));
                    }
                }
                if (ecoTax.printed() && saleEcoTax.signum() > 0) {
                    sb.append(formatLine(ecoTaxLabel(ecoTax), money(DF.format(saleEcoTax))));
                }
            }
        }
        sb.append("\n");
        // Payments
        sb.append(center(i18n.both("paper.reglement"), WIDTH)).append("\n");
        for (TicketPayment payment : ticket.payments) {
            String method = tenderLabel(payment);
            // LC-07-03-06: the rounding is stored with the sign the ledger needs
            // (so the settlements sum to the total) and PRINTED with the opposite
            // one, because on paper it reads as the adjustment to what the customer
            // hands over: "A PAYER 24,62 / ESPECES 24,60 / ARRONDI -0,02". This is
            // the single place that inversion happens.
            // LC-07-14-05: a foreign settlement states the three figures the
            // customer and the accounts both need — what was handed over, what it
            // was worth, and the rate that connects them.
            if (payment instanceof com.intermarche.pos.domain.payment.ForeignCurrencyPayment devise) {
                sb.append(formatLine(i18n.both("paper.devise") + safe(devise.currencyCode),
                        money(DF.format(payment.amount))));
                sb.append("  ").append(DF.format(devise.foreignAmount)).append(" ")
                        .append(safe(devise.currencyCode)).append(i18n.both("paper.taux"))
                        .append(devise.exchangeRate == null ? "" : devise.exchangeRate.toPlainString())
                        .append("\n");
                continue;
            }
            if (payment instanceof com.intermarche.pos.domain.payment.RoundingPayment) {
                sb.append(formatLine(i18n.both("paper.arrondi"), money(DF.format(payment.amount.negate()))));
                continue;
            }
            // LC-07-04-07/08: the cash a card cashback gave back is a negative
            // cash settlement on a sale; it names itself, and keeps its sign so
            // the settlements still add up to the total paid.
            if (payment instanceof com.intermarche.pos.domain.payment.CashPayment
                    && payment.amount.signum() < 0 && ticket.totalIncludingTax.signum() >= 0) {
                method = i18n.both("paper.cashback");
            }
            sb.append(formatLine(method, money(DF.format(payment.amount))));
            // THE CHANGE IS PRINTED, under the cash line it belongs to. The
            // settlement line carries what was APPLIED to the sale, which is not
            // what the customer handed over: a receipt showing only 24,60 for a
            // 50,00 note leaves them with no written trace of the 25,40 they were
            // given back, and no way to check it after they have left the lane.
            // Only when there is something to say: an exact sum, or a settlement
            // that recorded no tender, prints nothing.
            if (payment instanceof com.intermarche.pos.domain.payment.CashPayment cash
                    && cash.tenderedAmount != null
                    && cash.tenderedAmount.compareTo(payment.amount) > 0) {
                sb.append(formatLine(i18n.both("paper.donne"),
                        money(DF.format(cash.tenderedAmount))));
                sb.append(formatLine(i18n.both("paper.renduMonnaie"),
                        money(DF.format(cash.tenderedAmount.subtract(payment.amount)))));
            }
            // LC-08-04-06 [sic LC-07-09-06]: a credit settlement names its debtor
            // under its own line — date, account number, account name. A month
            // later this receipt is what the account is reconciled against, and an
            // amount owed by nobody cannot be reconciled against anything.
            if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment credit) {
                // The sale date is always set here: renderTicket formats
                // ticket.creationDate unconditionally in its header above, so a
                // null date would have thrown before reaching this credit line.
                String saleDay = ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                sb.append("  ").append(saleDay).append(i18n.both("paper.compte"))
                        .append(credit.accountNumber == null ? "" : credit.accountNumber)
                        .append("\n");
                sb.append("  ").append(credit.accountName == null ? "" : credit.accountName)
                        .append("\n");
            }
        }
        // Footer, with the online digital receipt link
        sb.append("\n");
        // BO-10-06-03: the software's fiscal certification number, on every
        // sale document where a country requires one; LC-08-04-23: on a
        // Portuguese document, within its legal mentions.
        sb.append(document == null ? fiscalCertificateLine() : fiscalFooter(document));
        if (ticket.digitalKey != null) {
            sb.append(center(i18n.both("paper.votreTicketLigne"), WIDTH)).append("\n");
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
        sb.append(center(i18n.both("paper.merciVotreVisite"), WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.bientot"), WIDTH)).append("\n");
        // BO-03-05-08: the administered web address, as a QR code by default.
        // Under the courtesy line, which is where a customer looks for what
        // the shop wants them to do next.
        sb.append(footerUrlBlock());
        // BO-03-05-03: the administered FOOT, the head's twin, printed after the
        // courtesy line; none administered, the single centred message of
        // BO-03-08-03 and -05 stands.
        java.util.List<String> footBanner = administeredBanner(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.TICKET_FOOTER, documentData);
        if (footBanner != null) {
            for (String bannerLine : footBanner) {
                sb.append(fillBannerLine(bannerLine, storeData(ticket))).append("\n");
            }
        } else {
            String footerMessage = posSettingsService.ticketFooterMessage();
            if (footerMessage != null && !footerMessage.isBlank()) {
                sb.append(center(fillStorePlaceholders(footerMessage, storeData(ticket)), WIDTH)).append("\n");
            }
        }
        // The ticket number as a barcode, last thing on the receipt: it is what a
        // return, a duplicate or a claim is looked up by, and typing it back by hand
        // is where the mistakes happen. The hardware bridge turns the directive into
        // the printer's own barcode command; the POS stays text.
        // BO-03-06-55: the store may reserve a RANGE for its gates or its game
        // kiosks by prefixing the printed code. The number itself is kept
        // whatever the prefix — it is what a return, a duplicate or a claim is
        // looked up by, and a prefix must not cost that.
        sb.append(saleIdentificationCode(ticket.ticketNumber));
        LOGGER.info("Exiting method renderTicket");
        return sb.toString();
    }

    /**
     * Whether an engine advantage prints UNDER its article (BO-03-03-23,
     * GB-01-07-10).
     *
     * <p>The shop's placement first: {@code LINE} puts every promotion under
     * the article it concerns, {@code FOOTER} gathers them all at the foot.
     * Anything else leaves the choice to the advantage itself, which is what
     * the register always did: under the article when the engine said LINE,
     * at the foot otherwise.
     *
     * @param placement the administered placement, possibly null
     * @param printMode the placement the advantage carries, possibly null
     * @return true when the advantage prints under its article
     */
    static boolean advantageUnderLine(String placement, String printMode) {
        if ("LINE".equals(placement)) {
            return true;
        }
        if ("FOOTER".equals(placement)) {
            return false;
        }
        return "LINE".equals(printMode);
    }

    /**
     * The short ADVANTAGE MESSAGE as the receipt prints it (BO-03-03-26): two
     * lines at most, each cut to the paper width — the format is two lines,
     * and a wrapped third would be a line the programme never wrote.
     *
     * @param messages the messages frozen on the sale
     * @return the lines and their newlines, empty when there is none
     */
    private String shortMessageLines(com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages messages) {
        StringBuilder lines = new StringBuilder();
        for (String line : messages.shortLines) {
            lines.append(line.length() > WIDTH ? line.substring(0, WIDTH) : line).append("\n");
        }
        return lines.toString();
    }

    /**
     * The COMMERCIAL MESSAGES as the receipt prints them (BO-03-03-27): the
     * first messages up to the administered count, each line cut to the paper
     * width, the messages set apart by an empty line.
     *
     * @param messages the messages frozen on the sale
     * @param max how many messages the shop lets through
     * @return the lines and their newlines, empty when there is none to print
     */
    private String commercialMessageLines(
            com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages messages, int max) {
        StringBuilder lines = new StringBuilder();
        int printed = 0;
        for (java.util.List<String> message : messages.commercial) {
            if (printed == max) {
                break;
            }
            if (printed > 0) {
                lines.append("\n");
            }
            for (String line : message) {
                lines.append(line.length() > WIDTH ? line.substring(0, WIDTH) : line).append("\n");
            }
            printed++;
        }
        return lines.toString();
    }

    /**
     * The code the receipt's barcode carries (BO-03-06-55): the administered
     * prefix, then the ticket number.
     *
     * @param ticketNumber the sale's number, possibly null
     * @return the code to encode, never null
     */
    private String ticketBarcode(String ticketNumber) {
        String number = safe(ticketNumber);
        String prefix = posSettingsService == null ? "" : posSettingsService.ticketBarcodePrefix();
        return prefix == null || prefix.isBlank() ? number : prefix.trim() + number;
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
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
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
        // LC-05-07-02: the recalculation of the kept basket reaches an administered
        // layout too, so a store that designed its own return ticket can print what
        // the return takes back instead of losing it.
        data.put("recalculation", java.util.Map.of(
                "status", safe(refund.recalculationStatus),
                "withdrawn", refund.withdrawnGenerosity == null ? ""
                        : DF.format(refund.withdrawnGenerosity),
                "keptAdvantages", safe(refund.keptAdvantages),
                "loyaltySent", refund.loyaltyRecalculationSent ? "1" : ""));
        java.util.List<java.util.Map<String, Object>> loyaltyLines = new java.util.ArrayList<>();
        for (com.intermarche.pos.domain.sale.RefundFidelityLine line
                : refund.fidelityLines == null ? java.util.List.<com.intermarche.pos.domain.sale.RefundFidelityLine>of()
                : refund.fidelityLines) {
            loyaltyLines.add(java.util.Map.of(
                    "type", safe(line.advantageTypeLabel),
                    "label", safe(line.label),
                    "amount", DF.format(line.amount)));
        }
        data.put("loyaltyLines", loyaltyLines);
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
        return storeFields(null);
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
        // A LinkedHashMap rather than Map.of: the totals now exceed the ten pairs the
        // factory accepts, and the order is the order a layout reads them in.
        java.util.Map<String, Object> totals = new java.util.LinkedHashMap<>();
        totals.put("ticketCount", String.valueOf(report.ticketCount));
        totals.put("includingTax", report.getTotalIncludingTaxFormatted());
        totals.put("refunds", report.getTotalRefundsFormatted());
        totals.put("theoreticalCash", report.getTheoreticalCashFormatted());
        totals.put("declaredCash", report.getDeclaredCashFormatted());
        totals.put("declaredVariance", report.getDeclaredCashVarianceFormatted());
        totals.put("openingFloat", session == null || session.openingFloat == null ? ""
                : DF.format(session.openingFloat));
        totals.put("countedCash", session == null || session.countedAmount == null ? ""
                : DF.format(session.countedAmount));
        totals.put("variance", session == null || session.variance == null ? ""
                : DF.format(session.variance));
        totals.put("withdrawn", session == null || session.withdrawnAmount == null ? ""
                : DF.format(session.withdrawnAmount));
        totals.put("otherTenders", report.getOtherMethodsTotalFormatted());
        totals.put("bankDeposit", report.getBankDepositTotalFormatted());
        data.put("totals", totals);
        java.util.List<java.util.Map<String, Object>> tenders = new java.util.ArrayList<>();
        for (CashSessionService.SessionReport.MethodRow row : report.getDetailedMethodRows()) {
            tenders.add(java.util.Map.of("label", methodLabel(row.method()), "key", row.method(),
                    "amount", row.amountFormatted()));
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
        return instrumentDocumentData(number, amount, null);
    }

    /**
     * Builds an INSTRUMENT voucher as an administered layout sees it, with the
     * end of validity the registry holds (BO-03-03-08).
     *
     * <p>The barcode travels as the DIRECTIVE, not as the number again: the
     * layout places the code where the shop wants it and the hardware bridge is
     * what encodes it, exactly as the built-in vouchers do. An instrument
     * carrying no number carries no barcode either — a directive over an empty
     * value prints the directive itself on the paper.
     *
     * @param number the registry number of the instrument
     * @param amount the amount it carries
     * @param validUntil the end of its validity, or null when it never expires
     *        or the shop does not state it
     * @return the document values
     */
    java.util.Map<String, Object> instrumentDocumentData(String number, BigDecimal amount,
            java.time.LocalDateTime validUntil) {
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
                "amount", amount == null ? "" : DF.format(amount),
                "validUntil", validUntil == null ? ""
                        : validUntil.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                "barcode", number == null || number.isBlank() ? "" : barcode(number)));
        return data;
    }

    /**
     * The end of validity a credit note states on paper (BO-03-03-08).
     *
     * <p>Read from the REGISTRY, which is authoritative on an instrument, and
     * read only when the shop asks for the date: a register that does not print
     * it must not pay a lookup for it, and the printers built by hand in the
     * unit tests reach no database.
     *
     * @param number the registry number of the note
     * @return the end of validity, or null when the shop does not state it, the
     *         number is unknown, or the note never expires
     */
    private java.time.LocalDateTime creditNoteExpiry(String number) {
        if (!posSettingsService.creditNotePrintExpiry() || number == null || number.isBlank()) {
            return null;
        }
        com.intermarche.pos.domain.payment.StoredValue instrument =
                com.intermarche.pos.domain.payment.StoredValue.findByNumber(number);
        return instrument == null ? null : instrument.validUntil;
    }

    /**
     * Builds only the sale footer code; other document and website codes keep their own policy.
     * @param number unique persisted ticket number
     * @return the final directive or an empty block (LC-08-01-03)
     */
    private String saleIdentificationCode(String number) {
        if (!posSettingsService.ticketBarcodeEnabled()) return "";
        String value = ticketBarcode(number);
        return "\n" + (posSettingsService.ticketBarcodeQr() ? qrCode(value) : barcode(value)) + "\n";
    }

    /**
     * Uses the administered anonymous wording only for the configured anonymous identifier.
     * @param ticket persisted sale, with no customer account lookup
     * @return printable identifier or anonymous wording (LC-08-06-02/-03, BO-10-04-16)
     */
    private String saleTaxIdLabel(Ticket ticket) {
        String value = safe(ticket.customerTaxId);
        String anonymous = posSettingsService.defaultTaxId();
        if (anonymous != null && !anonymous.isBlank() && anonymous.trim().equalsIgnoreCase(value)) {
            String label = posSettingsService.defaultTaxIdLabel();
            if (label != null && !label.isBlank()) return label.trim();
        }
        return value;
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
                "identificationCode", saleIdentificationCode(ticket.ticketNumber),
                "taxId", safe(ticket.customerTaxId),
                "taxIdLabel", saleTaxIdLabel(ticket),
                "taxIdCountry", com.intermarche.pos.domain.sale.FiscalDocument.countryOf(ticket.customerTaxId),
                "taxIdNumber", com.intermarche.pos.domain.sale.FiscalDocument.numberOf(ticket.customerTaxId),
                "duplicate", duplicata,
                "duplicateNumber", String.valueOf(duplicataNumber),
                "digitalPath", ticket.digitalKey == null ? ""
                        : "/t/" + ticket.id + "/" + ticket.digitalKey));
        data.put("lines", saleLines(ticket));
        data.put("totals", saleTotals(ticket));
        data.put("vatRows", saleVatRows(ticket));
        data.put("vatMentions", saleVatMentions(ticket));
        data.put("payments", salePayments(ticket));
        data.put("fidelity", fidelityData(ticket));
        return data;
    }

    /**
     * Describes the LOYALTY ZONE of a document, from what the closing froze on
     * the sale (BO-03-03-25, -29, -30, -31, -32, -33, -34).
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
     * Appends the frozen advantage lines of a sale, GROUPED as the back office
     * administers it — by advantage type, which is the default and the groups
     * then ordered by {@code advantageTypeOrder} ascending, or by advantage
     * category (imfid response A, BO-03-03-25/-33). The register prints the
     * received labels verbatim and never translates a code, and it orders by
     * the received order, never by any evaluation priority.
     * <p>
     * When NO line carries the administered key (rules predating the
     * referential), the output is the flat list exactly as before this grouping
     * existed — so an old-ticket duplicata is byte-identical. When some lines
     * carry it they are grouped, and any remaining ungrouped lines are appended
     * flat after the groups.
     *
     * @param sb the receipt buffer
     * @param ticket the sale whose frozen advantage lines are printed
     */
    private void appendFidelityAdvantages(StringBuilder sb, Ticket ticket) {
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> allLines =
                ticket.fidelityLines == null ? java.util.List.of() : ticket.fidelityLines;
        // BO-03-03-33: the shop says what the groups ARE — the advantage type,
        // which is the default and the historical print, or the category. Both
        // travel on the frozen line, so the choice costs no second request.
        String grouping = normalizeAdvantageGrouping(
                posSettingsService.fidelityAdvantageGrouping());
        boolean byCategory = GROUPING_CATEGORY.equals(grouping);
        if (GROUPING_NONE.equals(grouping) || !anyGrouped(allLines, byCategory)) {
            appendFlatAdvantages(sb, allLines);
            return;
        }
        java.util.LinkedHashMap<String,
                java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>> groups =
                new java.util.LinkedHashMap<>();
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> ungrouped =
                new java.util.ArrayList<>();
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : allLines) {
            String key = groupKeyOf(line, byCategory);
            if (key == null) {
                ungrouped.add(line);
            } else {
                groups.computeIfAbsent(key, absent -> new java.util.ArrayList<>()).add(line);
            }
        }
        java.util.List<String> orderedKeys = new java.util.ArrayList<>(groups.keySet());
        if (!byCategory) {
            // The received order, and only for the types: the categories come
            // out in the insertion order of the map above, which is the
            // category priority the programme communicated — the entries are
            // ordered by it at reception (BO-10-03-20).
            orderedKeys.sort(java.util.Comparator.comparingInt(key -> groupOrder(groups.get(key))));
        }
        for (String key : orderedKeys) {
            java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines = groups.get(key);
            String header = groupLabelOf(lines.get(0), byCategory);
            sb.append(header == null ? key : header).append("\n");
            appendFlatAdvantages(sb, lines);
        }
        appendFlatAdvantages(sb, ungrouped);
    }

    /**
     * Normalizes an administered advantage grouping; anything else — including
     * a grouping a back office knows and this register does not — falls back to
     * the advantage type, which is what the register has always printed.
     *
     * @param raw the administered value, possibly null or blank
     * @return {@link #GROUPING_TYPE}, {@link #GROUPING_CATEGORY} or
     *         {@link #GROUPING_NONE}
     */
    private String normalizeAdvantageGrouping(String raw) {
        if (raw == null) {
            return GROUPING_TYPE;
        }
        String value = raw.trim().toUpperCase();
        if (value.equals(GROUPING_CATEGORY) || value.equals(GROUPING_NONE)) {
            return value;
        }
        return GROUPING_TYPE;
    }

    /**
     * Whether at least one advantage line can be grouped at all.
     *
     * <p>None can — rules predating the referential — and the output is the
     * flat list exactly as before this grouping existed, so an old-ticket
     * duplicata stays byte-identical.
     *
     * @param lines the frozen advantage lines
     * @param byCategory whether the category is the grouping key
     * @return true when a line carries the grouping key
     */
    private boolean anyGrouped(
            java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines,
            boolean byCategory) {
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : lines) {
            if (groupKeyOf(line, byCategory) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The grouping key of one advantage line.
     *
     * @param line the frozen advantage line
     * @param byCategory whether the category is the grouping key
     * @return the key, or null when the line carries none
     */
    private String groupKeyOf(com.intermarche.pos.domain.sale.TicketFidelityLine line,
            boolean byCategory) {
        return byCategory ? line.advantageCategory : line.advantageType;
    }

    /**
     * The heading a group is printed under.
     *
     * @param line the group's first line
     * @param byCategory whether the category is the grouping key
     * @return the received label, or null when the line carries none
     */
    private String groupLabelOf(com.intermarche.pos.domain.sale.TicketFidelityLine line,
            boolean byCategory) {
        return byCategory ? line.advantageCategoryLabel : line.advantageTypeLabel;
    }

    /**
     * Appends advantage lines with no heading above them, each under its own
     * received label.
     *
     * @param sb the receipt buffer
     * @param lines the lines to append, possibly empty
     */
    private void appendFlatAdvantages(StringBuilder sb,
            java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines) {
        for (com.intermarche.pos.domain.sale.TicketFidelityLine line : lines) {
            sb.append(advantageVisual(line));
            sb.append(formatLine("  " + line.getLabel(), "+" + money(DF.format(line.amount))));
        }
    }

    /**
     * The VISUAL of an advantage, printed above its line when the back office
     * administered one ({@code BO-10-03-09}).
     *
     * <p>Above and not instead: the picture illustrates the advantage, it
     * does not state it. A customer whose printer drops images — or whose
     * shop administered none — reads the same line as before, which is the
     * rule every graphic addition on this paper follows.
     *
     * @param line the frozen advantage line
     * @return the image directive AND its newline, or an empty string
     */
    private String advantageVisual(com.intermarche.pos.domain.sale.TicketFidelityLine line) {
        if (storeImageService == null) {
            return "";
        }
        String picture = storeImageService.advantageVisual(line.ruleCode, line.advantageType);
        if (picture.isEmpty()) {
            return "";
        }
        String directive = ReceiptLogo.directive(picture,
                posSettingsService == null ? 0 : posSettingsService.logoWidthDots());
        return directive.isEmpty() ? "" : directive + "\n";
    }

    /**
     * Appends the "SOLDES DES CAGNOTTES" block (BO-10-02-33): a centred
     * heading, then one line per pot frozen on the sale — its label on the
     * left, cut to what the paper leaves beside the amount, its balance on the
     * right, nothing on the right for a pot the programme sent without one. A
     * sale without frozen pots prints nothing.
     *
     * @param sb the receipt buffer
     * @param ticket the sale whose frozen pots are printed
     */
    private void appendPotBalances(StringBuilder sb, Ticket ticket) {
        java.util.List<com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance> pots =
                com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance.decode(ticket.fidelityPotBalances);
        if (pots.isEmpty()) {
            return;
        }
        sb.append(center(i18n.both("paper.soldesCagnottes"), WIDTH)).append("\n");
        for (com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance pot : pots) {
            String amount = pot.balance == null ? "" : money(DF.format(pot.balance));
            // The label is cut, never the amount: the line stays on the paper.
            int room = WIDTH - amount.length() - 1;
            String label = pot.label.length() > room ? pot.label.substring(0, room) : pot.label;
            sb.append(formatLine(label, amount));
        }
    }

    /**
     * Appends the card's BALANCE ZONE (BO-03-03-31, BO-03-03-34).
     *
     * <p>Four figures, each printed only when the sale froze it: what the
     * balance held when the card was attached, what the day earned, what this
     * sale took on it, and what is left afterwards. A figure the register did
     * not vouch for is left out rather than printed as a zero, because a zero
     * on a receipt is a statement and not a silence.
     *
     * @param sb the receipt buffer
     * @param ticket the sale whose loyalty zone is printed
     */
    private void appendFidelityBalances(StringBuilder sb, Ticket ticket) {
        sb.append(center(i18n.both("paper.soldesCarte"), WIDTH)).append("\n");
        if (ticket.fidelityAvailableBalance != null) {
            sb.append(formatLine(i18n.both("paper.soldeDisponible"),
                    money(DF.format(ticket.fidelityAvailableBalance))));
        }
        if (ticket.fidelityEarnTotal != null) {
            sb.append(formatLine(i18n.both("paper.totalAvantagesJour"),
                    money(DF.format(ticket.fidelityEarnTotal))));
        }
        BigDecimal used = loyaltyPaid(ticket);
        if (used.signum() > 0) {
            sb.append(formatLine(i18n.both("paper.montantUtilise"), money(DF.format(used))));
        }
        if (ticket.fidelityBalanceAfter != null) {
            sb.append(formatLine(i18n.both("paper.nouveauSolde"),
                    money(DF.format(ticket.fidelityBalanceAfter))));
        } else if (ticket.fidelityProjectedAfterEarn != null) {
            // LC-03-05-03: the loyalty service returns the OLD balance and the
            // NEW one at every attachment — the new balance of an earn-only
            // sale is the projection the ledger will credit, and it is printed
            // with the same word a burn's confirmed balance uses. The
            // PREVISIONNEL label already names the figure in the advantages
            // block; this line is the balances zone the questionnaire asks
            // for, and it prints even when the block above is switched off.
            sb.append(formatLine(i18n.both("paper.nouveauSolde"),
                    money(DF.format(ticket.fidelityProjectedAfterEarn))));
        }
    }

    /**
     * What a line-level modification took off its article (BO-03-03-22).
     *
     * <p>Computed from the prices the sale FROZE — the original unit price and
     * the one that was charged — and not from {@code modifierValue}, which
     * means a different thing for each kind of modification: euros for a
     * rebate, a percentage for a discount, a new total for a forced price. One
     * subtraction says the same thing for all three, in the currency the
     * customer counts in.
     *
     * <p>Zero when there is nothing to state: no original price kept (a forced
     * price whose original the back office masks keeps none), no quantity, or a
     * modification that did not lower the price.
     *
     * @param line the sold line
     * @return the amount taken off this line, never negative
     */
    private BigDecimal lineDiscount(TicketLine line) {
        if (line == null || line.originalUnitPrice == null
                || line.unitPrice == null || line.quantity == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitSaving = line.originalUnitPrice.subtract(line.unitPrice);
        if (unitSaving.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return unitSaving.multiply(line.quantity).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Everything the sale took off its own prices (BO-03-03-24): the line-level
     * modifications plus the ticket discount.
     *
     * <p>The engine advantages are NOT in it. They are not a discount on a
     * price: they credit a loyalty balance, they are printed as such under
     * their own labels, and adding them here would tell the customer they paid
     * that much less than the total just above.
     *
     * <p>A cancelled line is outside the sale and never counts, exactly as in
     * the totals and in the VAT ventilation.
     *
     * @param ticket the sale
     * @return the cumulated immediate discounts, never negative
     */
    private BigDecimal immediateDiscountTotal(Ticket ticket) {
        BigDecimal total = ticket.globalDiscountApplied == null
                ? BigDecimal.ZERO : ticket.globalDiscountApplied;
        if (ticket.lines == null) {
            return total;
        }
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            total = total.add(lineDiscount(line));
        }
        return total;
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

    /**
     * An already-written amount, carrying the shop's CURRENCY SYMBOL on the
     * side the shop administered (BO-10-07-04, BO-10-07-05, BO-10-07-06).
     *
     * <p>The single place a printed amount acquires its symbol, which is the
     * whole point: before this, seventy-eight call sites each concatenated the
     * same literal, and no parameter could have reached them all.
     *
     * <p>Defaults reproduce what the register printed before: the symbol
     * {@code E}, after the amount, separated by one space. A shop that prints
     * nothing, or administers a blank symbol, gets the bare amount — the space
     * goes with the symbol rather than staying behind as a trailing blank.
     *
     * <p>A register built without a settings service — which only happens in a
     * test fixture that prints no parameterised document — keeps the built-in
     * symbol rather than failing the print.
     *
     * @param formattedAmount the amount, already written in the shop's format
     * @return the amount as it goes on the paper
     */
    private String money(String formattedAmount) {
        if (posSettingsService == null) {
            return formattedAmount + " " + BUILT_IN_CURRENCY_SYMBOL;
        }
        if (!posSettingsService.currencySymbolPrinted()) {
            return formattedAmount;
        }
        String symbol = safe(posSettingsService.currencySymbol()).trim();
        if (symbol.isEmpty()) {
            return formattedAmount;
        }
        if ("LEFT".equalsIgnoreCase(safe(posSettingsService.currencySymbolPosition()).trim())) {
            return symbol + " " + formattedAmount;
        }
        return formattedAmount + " " + symbol;
    }

    /**
     * The ADMINISTERED loyalty zone of this sale, or null when the store named
     * no gabarit for the case at hand (BO-10-03-05).
     *
     * <p>Four cases, and a fifth key for the grouped layout (BO-10-03-21): the
     * settings service picks the code, this method only turns that code into
     * paper. A code naming a gabarit that has not reached this register, or one
     * that will not compile, renders null — and the built-in zone prints, which
     * is the same doctrine the tender slips follow: a layout nobody can render
     * must not take a ticket away from the customer.
     *
     * @param ticket the sale being printed
     * @param documentData the data the sale receipt was built from
     * @return the laid-out zone, or null when there is none to print
     */
    private String administeredLoyaltyZone(Ticket ticket,
            java.util.Map<String, Object> documentData) {
        if (documentTemplateService == null) {
            return null;
        }
        boolean cardPresent = ticket.fidelityCard != null && !ticket.fidelityCard.isBlank();
        boolean grouped = "CATEGORY".equalsIgnoreCase(
                safe(posSettingsService.fidelityAdvantageGrouping()).trim());
        String code = posSettingsService.fidelityTemplateCode(
                cardPresent, ticket.fidelityUnavailable, grouped);
        if (code == null) {
            return null;
        }
        return documentTemplateService.renderTemplate(
                com.intermarche.pos.domain.setting.DocumentTemplate.findByCode(code),
                documentData);
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
        // BO-03-03-26 / -27: the programme's messages, as the shop lets them
        // through; BO-03-03-35: the balance of every pot of the card.
        com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages messages =
                com.intermarche.pos.ui.fidelity.ImfidClient.LoyaltyMessages.decode(ticket.fidelityMessages);
        data.put("shortMessage", posSettingsService.fidelityShortMessagePrinted()
                ? messages.shortLines : java.util.List.of());
        java.util.List<java.util.Map<String, Object>> commercial = new java.util.ArrayList<>();
        for (java.util.List<String> message : messages.commercial) {
            if (commercial.size() == posSettingsService.fidelityCommercialMessagesMax()) {
                break;
            }
            commercial.add(java.util.Map.of("lines", message));
        }
        data.put("commercialMessages", commercial);
        java.util.List<java.util.Map<String, Object>> pots = new java.util.ArrayList<>();
        for (com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance pot
                : com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance.decode(ticket.fidelityPotBalances)) {
            pots.add(java.util.Map.of("label", pot.label,
                    "balance", pot.balance == null ? "" : DF.format(pot.balance)));
        }
        data.put("pots", pots);
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
        return storeFields(ticket.store);
    }

    /**
     * The WHOLE store record a document header may read (BO-03-10-19/20):
     * name, address, city, intra-community VAT number, SIRET, phone and cheque
     * account, every field empty when the store or the field is absent.
     *
     * @param store the store record, or null
     * @return the fields, keyed as the templates read them
     */
    java.util.Map<String, Object> storeFields(com.intermarche.pos.domain.store.Store store) {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        com.intermarche.pos.domain.store.Address address = store == null ? null : store.address;
        fields.put("name", store == null ? "" : safe(store.name));
        fields.put("legalName", store == null ? "" : safe(store.legalName));
        fields.put("street", address == null ? "" : safe(address.streetLine1));
        fields.put("street2", address == null ? "" : safe(address.streetLine2));
        fields.put("postalCode", address == null ? "" : safe(address.postalCode));
        fields.put("city", address == null ? "" : safe(address.city));
        fields.put("country", address == null ? "" : safe(address.country));
        fields.put("siret", store == null ? "" : safe(store.siret));
        fields.put("vatNumber", store == null ? "" : safe(store.vatNumber));
        fields.put("phone", store == null ? "" : safe(store.phone));
        fields.put("bankAccount", store == null ? "" : safe(store.bankAccountNumber));
        return fields;
    }

    /** The placeholders a header or footer message may write, to their field (BO-03-10-20). */
    static final java.util.Map<String, String> STORE_PLACEHOLDERS = java.util.Map.ofEntries(
            java.util.Map.entry("NOMMAGASIN", "name"), java.util.Map.entry("MAGASIN", "name"),
            java.util.Map.entry("RAISONSOCIALE", "legalName"), java.util.Map.entry("ADRESSE", "street"),
            java.util.Map.entry("ADRESSE2", "street2"), java.util.Map.entry("CODEPOSTAL", "postalCode"),
            java.util.Map.entry("VILLE", "city"), java.util.Map.entry("PAYS", "country"),
            java.util.Map.entry("SIRET", "siret"), java.util.Map.entry("TVA", "vatNumber"),
            java.util.Map.entry("NUMEROTVA", "vatNumber"),
            java.util.Map.entry("TVAINTRACOMMUNAUTAIRE", "vatNumber"),
            java.util.Map.entry("TELEPHONE", "phone"), java.util.Map.entry("TEL", "phone"),
            java.util.Map.entry("COMPTE", "bankAccount"), java.util.Map.entry("NUMEROCOMPTE", "bankAccount"));

    /**
     * Replaces the store placeholders of an administered message with the data
     * of the store record (BO-03-10-20): {@code %Nom Magasin%} prints the store
     * name, {@code %Téléphone%} its phone, case, accents, spaces and underscores
     * not mattering. A placeholder naming nothing known is printed as written.
     *
     * @param text the administered message
     * @param store the store fields, as {@link #storeFields} builds them
     * @return the message with its placeholders filled
     */
    static String fillStorePlaceholders(String text, java.util.Map<String, Object> store) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%([^%]+)%").matcher(text);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String token = java.text.Normalizer.normalize(matcher.group(1), java.text.Normalizer.Form.NFD)
                    .replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
            String field = STORE_PLACEHOLDERS.get(token);
            String replacement = field == null ? matcher.group() : String.valueOf(store.get(field));
            matcher.appendReplacement(filled, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }

    /**
     * Fills the store placeholders of one line of an administered head or foot
     * template (BO-03-10-20): {@code %Nom Magasin%} written in the template
     * prints the store name, as it does in the header message. A line the
     * template centred is centred again on its filled text, so the name does
     * not shift the line off the middle of the paper.
     *
     * @param line the rendered line of the banner
     * @param store the store fields, as {@link #storeFields} builds them
     * @return the line with its placeholders filled
     */
    static String fillBannerLine(String line, java.util.Map<String, Object> store) {
        String filled = fillStorePlaceholders(line, store);
        if (filled.equals(line)) {
            return line;
        }
        com.intermarche.pos.service.DocumentLayout layout = new com.intermarche.pos.service.DocumentLayout(WIDTH);
        return layout.centre(line.strip()).equals(line) ? layout.centre(filled.strip()) : filled;
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
        int familyLevel = posSettingsService.ticketFamilyLevel();
        for (TicketLine line : TicketLineOrder.apply(ticket.lines, order,
                posSettingsService.ticketFamilyAlphabetical(),
                posSettingsService.ticketLineAttribute(),
                posSettingsService.ticketLineAttributeIsolate(), familyLevel)) {
            if (line.cancelled) continue;
            // A LinkedHashMap and no longer Map.of: the tax-excluded unit price
            // of BO-03-03-03 and the line modification of BO-03-03-22 are the
            // ninth, tenth and eleventh entries, one past what Map.of takes.
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("label", safe(line.productLabel));
            // BO-10-02-34: a layout reads the code only where the receipt
            // would print it — the article's flag, else the shop's parameter.
            row.put("ean", com.intermarche.pos.domain.catalog.attribute.ProductAttributes.codeShown(
                    line.product, posSettingsService.showEan()) ? safe(line.ean) : "");
            row.put("family", safe(TicketLineOrder.familyAt(line, familyLevel)));
            row.put("unit", safe(line.unitName));
            row.put("quantity", line.quantity == null ? "" : DF.format(line.quantity));
            row.put("unitPrice", line.unitPrice == null ? "" : DF.format(line.unitPrice));
            row.put("unitExcludingTax", unitExcludingTax(line));
            row.put("total", line.totalPrice == null ? "" : DF.format(line.totalPrice));
            row.put("vatRate", line.vatRate == null ? "" : line.vatRate.toPlainString());
            row.put("discountLabel", safe(line.modifierLabel));
            BigDecimal saved = lineDiscount(line);
            row.put("discount", saved.signum() == 0 ? "" : DF.format(saved));
            lines.add(row);
        }
        return lines;
    }

    /**
     * The tax-excluded unit price of a sold line (BO-03-03-03).
     *
     * <p>Removed from the price that was CHARGED, by the very rule the VAT
     * ventilation uses — so the column the layout prints and the table at the
     * foot of the same receipt cannot disagree, which is what BO-03-03-04 asks
     * of the two documents. A line carrying no price states none rather than a
     * zero.
     *
     * @param line the sold line
     * @return the tax-excluded unit price, already written, empty when the line
     *         carries no price
     */
    private String unitExcludingTax(TicketLine line) {
        if (line.unitPrice == null) {
            return "";
        }
        BigDecimal rate = line.vatRate == null ? BigDecimal.ZERO : line.vatRate;
        return DF.format(line.unitPrice.divide(BigDecimal.ONE.add(rate), 2,
                java.math.RoundingMode.HALF_UP));
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
        BigDecimal immediate = immediateDiscountTotal(ticket);
        return java.util.Map.of(
                "excludingTax", DF.format(includingTax.subtract(vat)),
                "vat", DF.format(vat),
                "includingTax", DF.format(includingTax),
                "discount", ticket.globalDiscountApplied == null ? ""
                        : DF.format(ticket.globalDiscountApplied),
                "immediateDiscount", immediate.signum() == 0 ? "" : DF.format(immediate));
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
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        if (ticket.lines != null) {
            for (TicketLine line : ticket.lines) {
                if (line.cancelled) continue;
                breakdown.add(line.vatRate, line.vatNumber, line.totalPrice);
            }
        }
        // BO-03-03-04: the code beside each rate, where the shop prints the
        // codes — the same letter the receipt's own table states.
        boolean codes = posSettingsService.ticketVatCode();
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            // BO-02-02-02: the legal mention of the regime, so an administered
            // layout can print it under its own ventilation line.
            rows.add(java.util.Map.of(
                    "rate", bucket.getRateFormatted(),
                    "base", DF.format(bucket.totalExcludingTax),
                    "vat", DF.format(bucket.vatAmount),
                    "code", codes ? safe(vatLetter(bucket.rate, bucket.number)) : "",
                    "mention", safe(mentionOf(bucket))));
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
            // The word the CUSTOMER reads, the very one the built-in layout
            // prints: an administered layout that said "CASH" where the paper
            // says "ESPECES" would make the same sale read two ways depending
            // on whether the shop wrote a template (LC-07-03-06).
            payments.add(java.util.Map.of(
                    "label", tenderLabel(payment),
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
     * Prints a Portuguese document issued on its own, after the sale: the credit
     * note and the invoice of a conversion, a receipt (LC-08-04-21, -22).
     *
     * @param document the issued document
     */
    public void printFiscalDocument(com.intermarche.pos.domain.sale.FiscalDocument document) {
        LOGGER.info("Entering method printFiscalDocument with document: " + document.number);
        hardwareService.printReceipt(renderFiscalDocument(document, false, 0));
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printFiscalDocument");
    }

    /**
     * Renders a Portuguese document on its own (LC-08-01-06, LC-08-04-21, -22):
     * the store and its NIF, the document's identity, the lines it states, its
     * totals and VAT ventilation, the payments it carries, its legal mentions.
     *
     * @param document the document
     * @param duplicata whether the DUPLICATA banner is printed
     * @param duplicataNumber the duplicata's rank, meaningless when not a duplicata
     * @return the document as printable text
     */
    String renderFiscalDocument(com.intermarche.pos.domain.sale.FiscalDocument document,
            boolean duplicata, int duplicataNumber) {
        Ticket ticket = document.ticketId == null ? null : Ticket.findById(document.ticketId);
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (ticket != null && ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
            if (ticket.store.address != null) {
                sb.append(center(ticket.store.address.city, WIDTH)).append("\n");
            }
            if (ticket.store.vatNumber != null && !ticket.store.vatNumber.isBlank()) {
                sb.append(center(i18n.get("doc.nif") + ticket.store.vatNumber, WIDTH)).append("\n");
            }
        }
        if (duplicata) {
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(center(String.format(i18n.get("paper.duplicata"), duplicataNumber), WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(fiscalHeader(document));
        sb.append(String.format(i18n.get("paper.date"),
                document.issuedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (ticket != null) {
            sb.append(String.format(i18n.get("paper.ticket"), ticket.ticketNumber));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        boolean receipt = document.documentType == com.intermarche.pos.domain.sale.FiscalDocument.Type.RC;
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        if (ticket != null) {
            for (TicketLine line : ticket.lines) {
                if (!document.covers(line)) {
                    continue;
                }
                breakdown.add(line.vatRate, line.vatNumber, line.totalPrice, line.vatExempt, line.thirdPartySale);
                if (receipt) {
                    // A receipt states what was paid, not what was sold: its
                    // lines only feed the legal mentions below (BO-02-02-02).
                    continue;
                }
                sb.append(line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel)
                        .append("\n");
                String detail = com.intermarche.pos.domain.catalog.UnitFormat.orDefault(
                        posSettingsService.unitFormat()).detailLine(
                        line.quantity, line.unitPrice, line.unitName,
                        line.plu != null && !line.plu.isEmpty(),
                        line.embeddedWeight, line.embeddedUnitPrice, "E");
                if (!detail.isBlank()) {
                    sb.append("  ").append(detail).append("\n");
                }
                sb.append(String.format("%" + WIDTH + "s%n", DF.format(line.totalPrice)));
            }
            if (!receipt) {
                sb.append("-".repeat(WIDTH)).append("\n");
            }
        }
        sb.append(formatLine(receipt ? i18n.both("paper.ptValorRecebido") : i18n.both("paper.totalTtc"),
                money(DF.format(document.grossTotal))));
        if (!receipt) {
            sb.append(formatLine(i18n.both("paper.dontTva"), money(DF.format(document.taxTotal))));
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                // BO-02-02-01: the regime's code, where the shop prints codes, so
                // two regimes of one percentage read apart on the document too;
                // the same headings as the sale ticket — EXO for an exemption
                // (BO-02-03-26), the third-party mention (BO-02-03-42).
                sb.append(vatVentilationLine(ventilationLabel(bucket), bucket));
            }
        }
        // BO-02-02-02: the legal mentions of the regimes this document states,
        // on EVERY Portuguese document — the simplified invoice, the invoice,
        // the credit note and the receipt alike.
        sb.append(vatLegalMentions(breakdown));
        // A credit note cancelling a document refunds nothing at the till: it
        // states no payment. The other documents state what they were paid with.
        boolean settled = ticket != null
                && document.documentType != com.intermarche.pos.domain.sale.FiscalDocument.Type.NC;
        if (settled) {
            sb.append("\n");
            sb.append(center(i18n.both("paper.reglement"), WIDTH)).append("\n");
            java.util.Map<Integer, BigDecimal> shares = document.shares();
            for (int index = 0; index < ticket.payments.size(); index++) {
                TicketPayment payment = ticket.payments.get(index);
                BigDecimal share = shares == null ? payment.amount : shares.get(index);
                if (share != null) {
                    sb.append(formatLine(tenderLabel(payment), money(DF.format(share))));
                }
            }
        }
        sb.append("\n");
        sb.append(fiscalFooter(document));
        return sb.toString();
    }

    /**
     * The identity of a Portuguese document as its paper states it (LC-08-01-05,
     * LC-08-04-18, -21): its title and number, the customer it states, the
     * document it refers to and, for a credit note cancelling a document, why.
     *
     * <p>The customer's NIF is stated on every document, with its country code,
     * CONSUMIDOR FINAL for nobody (LC-08-06-11).
     *
     * @param document the document
     * @return the block, each line ending with a line feed
     */
    private String fiscalHeader(com.intermarche.pos.domain.sale.FiscalDocument document) {
        StringBuilder sb = new StringBuilder();
        String title = switch (document.documentType) {
            case FS -> i18n.get("paper.ptFS");
            case FT -> i18n.get("paper.ptFT");
            case NC -> i18n.get("paper.ptNC");
            case RC -> i18n.get("paper.ptRC");
        };
        sb.append(center(title, WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.ptNumero"), document.number));
        // LC-08-06-11: the customer's country and number, CONSUMIDOR FINAL in
        // place of the final consumer's 999999990.
        String taxId = com.intermarche.pos.domain.sale.FiscalDocument.isFinalConsumer(document.customerTaxId)
                ? i18n.get("paper.ptConsumidorFinal")
                : com.intermarche.pos.domain.sale.FiscalDocument.countryOf(document.customerTaxId) + " "
                        + com.intermarche.pos.domain.sale.FiscalDocument.numberOf(document.customerTaxId);
        if (document.customerName != null) {
            sb.append(i18n.get("doc.nif")).append(taxId).append("\n");
            for (String line : wrap(document.customerName, WIDTH)) {
                sb.append(line).append("\n");
            }
            for (String line : wrap(safe(document.customerAddress), WIDTH)) {
                sb.append(line).append("\n");
            }
        } else {
            sb.append(i18n.get("doc.nif")).append(taxId).append("\n");
        }
        if (document.sourceNumber != null) {
            for (String line : wrap(i18n.get("paper.ptReferencia") + " " + document.sourceNumber, WIDTH)) {
                sb.append(line).append("\n");
            }
        }
        if (document.documentType == com.intermarche.pos.domain.sale.FiscalDocument.Type.NC
                && document.refundId == null) {
            for (String line : wrap(i18n.get("paper.ptMotivoConversao"), WIDTH)) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * The legal mentions at the foot of a Portuguese document (LC-08-04-23): the
     * four characters of its signature and the certification of the software,
     * its ATCUD, its fiscal QR code.
     *
     * @param document the document
     * @return the block, each line ending with a line feed
     */
    private String fiscalFooter(com.intermarche.pos.domain.sale.FiscalDocument document) {
        StringBuilder sb = new StringBuilder();
        String certified = document.hashCharacters() + "-" + i18n.format("paper.ptProcessado",
                com.intermarche.pos.ui.invoice.PortugueseFiscalService.certificate(
                        posSettingsService.fiscalCertificateNumber()));
        for (String line : wrap(certified, WIDTH)) {
            sb.append(center(line, WIDTH)).append("\n");
        }
        sb.append(center("ATCUD: " + document.atcud, WIDTH)).append("\n");
        sb.append(qrCode(document.qrPayload)).append("\n");
        return sb.toString();
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
    /**
     * The FISCAL CERTIFICATION line, centred, or an empty string where no
     * number is administered (BO-10-06-03).
     *
     * <p>Printed on every sale and return document of the register, which is
     * what the Portuguese administration asks and what no other country
     * forbids: a shop that administers nothing prints nothing, so the line
     * costs the receipts of eleven countries exactly nothing.
     *
     * @return the line to append, empty when there is no number
     */
    private String fiscalCertificateLine() {
        String number = safe(posSettingsService.fiscalCertificateNumber()).trim();
        if (number.isEmpty()) {
            return "";
        }
        return center(i18n.both("paper.certificatFiscal") + " " + number, WIDTH) + "\n";
    }

    /**
     * Marks a printed line as ENLARGED — double height and double width — for
     * the hardware bridge (BO-10-07-14).
     *
     * <p>The same shape as the bold directive the layout engine already emits,
     * and the same contract: the markers do NOT count as characters of the
     * line, so the line is aligned FIRST and wrapped afterwards. A bridge that
     * does not know the directive prints it, which is visible on the paper
     * rather than silently ignored.
     *
     * @param line the already-aligned line, newline included
     * @return the line between its two markers
     */
    private static String large(String line) {
        return "[[DOUBLE]]" + line + "[[/DOUBLE]]";
    }

    /**
     * The ECO-PARTICIPATION borne by one sold line — the article's tariff times
     * the quantity — or zero when the article carries none (BO-10-06-06).
     *
     * <p>Read from the article as the INVOICE reads it, and not frozen on the
     * line: the two documents of one sale must state the same figure, which is
     * the rule {@code BO-03-03-04} already imposes on the VAT table.
     *
     * @param line the sold line
     * @param ecoTax the eco-tax read (BO-10-06-05)
     * @return the eco-tax of that line, never null
     */
    private BigDecimal ecoTaxOf(TicketLine line,
            com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition ecoTax) {
        if (line.product == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal unit = ecoTax.unitAmount(line.product);
        BigDecimal quantity = line.quantity == null ? BigDecimal.ONE : line.quantity;
        return unit.multiply(quantity);
    }

    /**
     * The wording an eco-tax prints under (BO-10-06-05): the administered
     * label, the register's eco-participation wording for the built-in
     * attribute, the attribute code otherwise.
     *
     * @param ecoTax the eco-tax
     * @return the printed label, never null
     */
    private String ecoTaxLabel(com.intermarche.pos.domain.catalog.attribute.EcoTaxDefinition ecoTax) {
        if (ecoTax.label() != null) {
            return ecoTax.label();
        }
        return com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.ECO_TAX.equals(ecoTax.code())
                ? i18n.both("paper.ecoParticipation") : ecoTax.code();
    }

    /**
     * The administered rounding of the VAT table (BO-10-02-18), half-up for a
     * printer built without its parameters.
     *
     * @return the rounding mode, null meaning half-up
     */
    private java.math.RoundingMode roundingMode() {
        return posSettingsService == null ? null : posSettingsService.calcRoundingMode();
    }

    /**
     * Lays out a label and an amount, the amount in BOLD where the shop asks
     * for it (BO-10-07-15) — aligned first, marked afterwards, so the markers
     * are not characters of the line.
     *
     * @param left the label
     * @param amount the written amount
     * @param bold whether the amount is emphasised
     * @return the line, newline included
     */
    private String amountLine(String left, String amount, boolean bold) {
        String line = LAYOUT.line(left, amount);
        if (!bold) {
            return line + "\n";
        }
        return line.substring(0, line.length() - amount.length()) + "[[BOLD]]" + amount + "[[/BOLD]]\n";
    }

    /**
     * Writes a CANCELLED line where the shop prints them (BO-10-08-30): its
     * label, then the cancellation mention beside the amount it no longer
     * charges.
     *
     * @param line the cancelled line
     * @return the two printed lines, newline included
     */
    private String cancelledLine(TicketLine line) {
        String label = line.productLabel.length() > 20 ? line.productLabel.substring(0, 20) : line.productLabel;
        return label + "\n" + formatLine("  " + i18n.both("paper.ligneAnnulee"), money(DF.format(line.totalPrice)));
    }

    /**
     * Details the identical articles one per line where the shop does not
     * want them grouped (BO-10-08-31): a line of N identical units becomes N
     * lines of one, the receipt's totals untouched.
     *
     * @param lines the lines in printing order
     * @param valuations the engine traces, keyed by line uid
     * @return the lines to print, in the same order
     */
    private java.util.List<TicketLine> detailIdentical(java.util.List<TicketLine> lines,
            java.util.Map<String, TicketLineValuation> valuations) {
        java.util.List<TicketLine> detailed = new java.util.ArrayList<>();
        for (TicketLine line : lines) {
            int units = detailableUnits(line, valuations);
            if (units < 2) {
                detailed.add(line);
                continue;
            }
            for (int unit = 1; unit <= units; unit++) {
                detailed.add(unitCopy(line, unit == units));
            }
        }
        return detailed;
    }

    /**
     * How many unit lines a line can be detailed into: a whole number of
     * identical pieces at their unit price, nothing else — a weighing, a
     * measure, a modified or engine-valued line or a recalled one keeps its
     * single line, its sub-lines speaking for the whole quantity.
     *
     * @param line the line
     * @param valuations the engine traces, keyed by line uid
     * @return the number of units, zero when the line is kept whole
     */
    private int detailableUnits(TicketLine line, java.util.Map<String, TicketLineValuation> valuations) {
        if (line.cancelled || (line.plu != null && !line.plu.isEmpty())
                || (line.unitName != null && !line.unitName.isBlank())
                || line.modifierLabel != null || line.orderedQuantity != null
                || valuations.containsKey(line.lineUid)
                || line.quantity.stripTrailingZeros().scale() > 0
                || line.unitPrice.multiply(line.quantity).compareTo(line.totalPrice) != 0) {
            return 0;
        }
        return line.quantity.intValue();
    }

    /**
     * One unit of a detailed line (BO-10-08-31): the same article at its unit
     * price, the subtotal marker kept on the last unit only.
     *
     * @param line the detailed line
     * @param last whether this is the last unit
     * @return the printed unit, never persisted
     */
    private TicketLine unitCopy(TicketLine line, boolean last) {
        TicketLine unit = new TicketLine();
        unit.lineUid = line.lineUid;
        unit.product = line.product;
        unit.ean = line.ean;
        unit.productLabel = line.productLabel;
        unit.quantity = BigDecimal.ONE;
        unit.unitPrice = line.unitPrice;
        unit.totalPrice = line.unitPrice;
        unit.vatRate = line.vatRate;
        unit.familyCode = line.familyCode;
        unit.familyLabel = line.familyLabel;
        unit.secondCode = line.secondCode;
        unit.returnLine = line.returnLine;
        unit.subtotalBoundary = last && line.subtotalBoundary;
        return unit;
    }

    /**
     * Emits the barcode directive carrying a payload.
     *
     * <p>The gabarit describes, the driver prints: this layer never decides
     * the symbology nor the module width, it names the value and leaves the
     * rest to the printer that knows its own head.
     *
     * @param value the payload to encode
     * @return the directive line WITHOUT its newline
     */
    private static String barcode(String value) {
        return "[[BARCODE " + value + "]]";
    }

    /**
     * Emits the QR directive carrying a payload ({@code BO-03-05-08}).
     *
     * <p>A DIRECTIVE AND NOT A BITMAP, like the bar code beside it: a thermal
     * head encodes a QR itself from the text, far better and far smaller than
     * a raster the register would compute and ship dot by dot. The bridge
     * turns the marker into the printer's own QR command, and a printer that
     * knows none shows the marker — which a paramétreur can read — rather
     * than a page of control bytes.
     *
     * @param value the payload the code carries
     * @return the directive line, without its newline
     */
    private static String qrCode(String value) {
        return "[[QR " + value + "]]";
    }

    /**
     * The FOOT-OF-TICKET address block ({@code BO-03-05-08}): the administered
     * address, rendered as the shop asked — a QR code, a bar code, or the
     * address written out.
     *
     * <p>Nothing administered, nothing printed, and the document is
     * byte-for-byte the one printed before this block existed. An unknown
     * format answers the QR code rather than nothing: a shop that typed a
     * word this version does not know still wanted its address on the paper.
     *
     * @return the block and its newline, or an empty string
     */
    String footerUrlBlock() {
        if (posSettingsService == null) {
            return "";
        }
        String url = safe(posSettingsService.ticketFooterUrl()).trim();
        if (url.isEmpty()) {
            return "";
        }
        String format = safe(posSettingsService.ticketFooterUrlFormat()).trim().toUpperCase();
        if ("AUCUN".equals(format)) {
            return "";
        }
        if ("TEXTE".equals(format)) {
            StringBuilder written = new StringBuilder();
            for (String line : wrap(url, WIDTH)) {
                written.append(center(line, WIDTH)).append("\n");
            }
            return written.toString();
        }
        return ("BARCODE".equals(format) ? barcode(url) : qrCode(url)) + "\n";
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
            StringBuilder body = new StringBuilder(administered);
            if (!report.closing) appendOperationRows(body, report);
            hardwareService.printReceipt(body.toString());
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printSessionReport");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append(center(report.closing ? i18n.get("paper.rapportZCloture") : i18n.get("paper.rapportXLecture"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.session"), session.sessionNumber));
        sb.append(String.format(i18n.get("paper.caisse"), session.terminalId));
        sb.append(String.format(i18n.get("paper.ouverte"), session.openingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (report.closing && session.closingDate != null) {
            sb.append(String.format(i18n.get("paper.fermee"), session.closingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.ticketsClos"), String.valueOf(report.ticketCount)));
        sb.append(formatLine(i18n.get("paper.caTtc"), money(DF.format(report.totalIncludingTax))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.reglements"), WIDTH)).append("\n");
        // BO-03-02-25: a tender the back office does not put in detail is not
        // hidden, it is summed — the report still balances, it just says less
        // about tenders the store does not want line by line.
        for (var row : report.getDetailedMethodRows()) {
            sb.append(formatLine(methodLabel(row.method()), money(row.amountFormatted())));
        }
        if (report.isCarryingUndetailedMethods()) {
            sb.append(formatLine(i18n.get("paper.autresReglements"),
                    money(report.getOtherMethodsTotalFormatted())));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report.totalRefunds.signum() > 0) {
            sb.append(formatLine(i18n.get("paper.remboursements"), money(DF.format(report.totalRefunds))));
        }
        // BO-03-02-21 and BO-03-02-30: what goes to the bank and what goes to the
        // fidelity programme are stated only when the store administered tenders
        // for them — a nil line would say something the store never asked.
        if (report.bankDepositTotal.signum() > 0) {
            sb.append(formatLine(i18n.get("paper.remiseBanque"),
                    money(report.getBankDepositTotalFormatted())));
        }
        if (report.fidelityReportedTotal.signum() > 0) {
            sb.append(formatLine(i18n.get("paper.dontRemonteeFidelite"),
                    money(report.getFidelityReportedTotalFormatted())));
        }
        sb.append(formatLine(i18n.get("paper.fondCaisse"), money(DF.format(session.openingFloat))));
        sb.append(formatLine(i18n.get("paper.especesTheorique"), money(DF.format(report.theoreticalCash))));
        // The cashier's own declaration, stated only when they made one, and stated
        // next to the theoretical — the comparison is the whole point of asking.
        if (report.isDeclared()) {
            sb.append(formatLine(i18n.get("paper.especesDeclarees"), money(DF.format(report.declaredCash))));
            sb.append(formatLine(i18n.get("paper.ecartDeclaration"),
                    money(DF.format(report.declaredCashVariance))));
        }
        if (report.closing) {
            sb.append(formatLine(i18n.get("paper.especesComptees"), money(DF.format(session.countedAmount))));
            sb.append(formatLine(i18n.get("paper.ecart"), money(DF.format(session.variance))));
            sb.append(formatLine(i18n.get("paper.prelevement"), money(DF.format(session.withdrawnAmount))));
        }
        if (!report.closing) appendOperationRows(sb, report);
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
        printRefund(refundId, true);
    }

    /** Prints a return with an optional cut before its WCL customer frame (LC-07-04-11).
     * @param refundId persisted return identifier
     * @param cut whether to cut immediately
     */
    @Transactional
    public void printRefund(Long refundId, boolean cut) {
        LOGGER.info("Entering method printRefund with refundId: " + refundId);
        Refund refund = Refund.findById(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("Remboursement introuvable pour impression : " + refundId);
        }
        Ticket original = Ticket.findById(refund.originalTicketId);
        // LC-05-07-03: the credit note the return is stated on, if any.
        com.intermarche.pos.domain.sale.FiscalDocument creditNote =
                com.intermarche.pos.domain.sale.FiscalDocument.forRefund(refund.id);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.REFUND_RECEIPT,
                refundDocumentData(refund, original));
        if (administered != null) {
            if (creditNote != null) {
                administered = administered + fiscalHeader(creditNote) + fiscalFooter(creditNote);
            }
            printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.RETURN, refund.id, administered, PrintPolicy.DOCUMENT_RETURN, cut);
            LOGGER.info("Exiting method printRefund");
            return;
        }
        StringBuilder sb = new StringBuilder();
        // Header
        if (original != null && original.store != null) {
            sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
            sb.append(center(original.store.name, WIDTH)).append("\n");
            if (original.store.address != null) {
                sb.append(center(original.store.address.city, WIDTH)).append("\n");
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Refund title
        // BO-03-04-12: the document's name, administered or the register's.
        sb.append(center(documentTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.REFUND_RECEIPT,
                i18n.both("paper.ticketRetour")), WIDTH)).append("\n");
        if (creditNote != null) {
            sb.append(fiscalHeader(creditNote));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        // Refund info
        sb.append(String.format(i18n.get("paper.date"), refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (original != null) {
            sb.append(String.format(i18n.get("paper.ticketOriginal"), original.ticketNumber));
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
        sb.append(formatLine(i18n.both("paper.totalRembourse"), money(DF.format(refund.totalAmount))));
        if (refund.refundMethod != null) {
            sb.append(formatLine(i18n.both("paper.mode"), refundMethodLabel(refund.refundMethod)));
        }
        // LC-05-05-03: the chosen return reason is printed on the return ticket
        // when the store administers it (return.print-reason) and a reason was
        // actually chosen; otherwise the paper stays as before.
        if (posSettingsService.printReturnReason()
                && refund.returnReason != null && !refund.returnReason.isBlank()) {
            sb.append(String.format(i18n.get("paper.motif"), refund.returnReason));
        }
        VatBreakdown refundBreakdown = new VatBreakdown(roundingMode());
        for (RefundLine line : refund.lines) {
            refundBreakdown.add(line.vatRate, regimeOfOriginal(line),
                    line.price.multiply(line.quantity).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        // BO-10-06-04: same VAT-breakdown gate on the refund ticket.
        if (posSettingsService.vatBreakdownEnabled()) {
            for (VatBreakdown.Bucket bucket : refundBreakdown.getBuckets()) {
                sb.append(vatVentilationLine(bucket.getRateFormatted(), bucket));
            }
        }
        // BO-02-02-02: the same legal mentions on the return document, which
        // states a restituted tax and owes the customer the same sentence.
        sb.append(vatLegalMentions(refundBreakdown));
        sb.append(refundRecalculationBlock(refund));
        sb.append("\n");
        // Footer
        sb.append("\n");
        // BO-10-06-03: and on every return document, by the same rule;
        // LC-05-07-03: within the credit note's legal mentions.
        sb.append(creditNote == null ? fiscalCertificateLine() : fiscalFooter(creditNote));
        sb.append(center(i18n.both("paper.merciVotreVisite"), WIDTH)).append("\n");
        // BO-03-05-08: the same administered address on the return document.
        sb.append(footerUrlBlock());
        // BO-03-09-09: the return carries its unique barcode, as the sale does,
        // when the register's ticket barcode is activated, in its format.
        sb.append(saleIdentificationCode(refund.refundNumber));
        printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.RETURN, refund.id, sb.toString(), PrintPolicy.DOCUMENT_RETURN, cut);
        LOGGER.info("Exiting method printRefund");
    }

    /**
     * The recalculation block of a return ticket (LC-05-07-02): what the return
     * takes back beyond the refunded price, what the kept basket still earns, and
     * whether the loyalty programme was asked to recompute.
     *
     * <p>Three things are printed, and only what the register can vouch for: the
     * advantage withdrawn, which the engine's re-pricing of the kept basket
     * establishes to the centime; the advantages that survive, restated from the
     * engine's own labels; and the fact that the décagnottage was handed to the
     * loyalty service, which owns that amount — never a figure this register
     * computed for it. A recalculation that could not run says so rather than
     * printing silence, because a customer who loses an advantage must read why.
     *
     * @param refund the refund being printed
     * @return the block and its separator, or an empty string when no
     *         recalculation applied to this refund
     */
    private String refundRecalculationBlock(Refund refund) {
        LOGGER.info("Entering method refundRecalculationBlock");
        String status = refund.recalculationStatus;
        boolean recomputed = "RECOMPUTED".equals(status);
        boolean unavailable = "UNAVAILABLE".equals(status);
        if (!recomputed && !unavailable && !refund.loyaltyRecalculationSent) {
            LOGGER.info("Exiting method refundRecalculationBlock");
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.recalculAvantages"), WIDTH)).append("\n");
        if (recomputed) {
            BigDecimal withdrawn = refund.withdrawnGenerosity == null
                    ? BigDecimal.ZERO : refund.withdrawnGenerosity;
            sb.append(formatLine(i18n.both("paper.avantageRepris"), money(DF.format(withdrawn))));
            if (refund.keptAdvantages != null && !refund.keptAdvantages.isBlank()) {
                sb.append(i18n.get("paper.avantagesConserves"));
                for (String label : refund.keptAdvantages.split(" \\| ")) {
                    sb.append(String.format("  - %s%n",
                            label.length() > WIDTH - 4 ? label.substring(0, WIDTH - 4) : label));
                }
            }
        }
        if (unavailable) {
            sb.append(i18n.get("paper.moteurIndisponible"));
            sb.append(i18n.get("paper.recalculNonEffectue"));
        }
        if (refund.loyaltyRecalculationSent) {
            sb.append(i18n.get("paper.cagnotteCoupons"));
            sb.append(i18n.get("paper.recalculTransmisProgramme"));
        }
        // LC-05-07-02: what the loyalty programme takes back, line by line —
        // cagnotte, purchase vouchers, coupons — and its total.
        if (refund.fidelityLines != null && !refund.fidelityLines.isEmpty()) {
            sb.append(i18n.both("paper.decagnottage")).append("\n");
            BigDecimal total = BigDecimal.ZERO;
            for (com.intermarche.pos.domain.sale.RefundFidelityLine line : refund.fidelityLines) {
                String label = line.advantageTypeLabel == null || line.advantageTypeLabel.isBlank()
                        ? safe(line.label) : line.advantageTypeLabel + " " + safe(line.label);
                if (label.length() > 26) {
                    label = label.substring(0, 26);
                }
                sb.append(formatLine("  " + label, "-" + money(DF.format(line.amount))));
                total = total.add(line.amount);
            }
            sb.append(formatLine(i18n.both("paper.totalRetire"), "-" + money(DF.format(total))));
        }
        LOGGER.info("Exiting method refundRecalculationBlock");
        return sb.toString();
    }

    /**
     * The logo the back office administered for this store
     * ({@code BO-03-03-15}), or an empty string when it administered none —
     * the register then printing the logo of its installation.
     *
     * <p>Null-tolerant on the service, like the settings around it: a printer
     * built by hand in a unit test carries none, and a receipt must still
     * print.
     *
     * @return the administered logo as a data URI, never null
     */
    private String administeredLogo() {
        return storeImageService == null ? "" : storeImageService.receiptLogo();
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
        // BO-03-03-14: the head's own width, as the shop states it. A printer
        // built by hand in a unit test answers zero, which the logo reads as
        // "no width administered" and renders at its default.
        String logo = ReceiptLogo.directive(administeredLogo(),
                posSettingsService.logoWidthDots());
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
        String logo = ReceiptLogo.directive(administeredLogo());
        return logo.isEmpty() ? center(i18n.get("paper.intermarche"), WIDTH) + "\n" : logo + "\n";
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
        hardwareService.printReceipt(renderTrainingReceipt(state));
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printTrainingReceipt");
    }

    /**
     * Renders the training receipt of the sale in progress, as it prints: in the
     * customer's language when one was chosen (LC-13-06-13), and as the back
     * office keeps it (LC-13-05-06).
     *
     * @param state the current POS state
     * @return the receipt as printable text
     */
    public String renderTrainingReceipt(com.intermarche.pos.ui.PosState state) {
        return i18n.forCustomer(() -> trainingReceipt(state));
    }

    /**
     * Lays out the training receipt from the in-memory cart and payments.
     *
     * @param state the current POS state
     * @return the receipt as printable text
     */
    private String trainingReceipt(com.intermarche.pos.ui.PosState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center("*".repeat(WIDTH), WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.modeFormation"), WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.ticketNonValable"), WIDTH)).append("\n");
        sb.append(center("*".repeat(WIDTH), WIDTH)).append("\n\n");
        for (com.intermarche.pos.ui.ticket.TicketState.TicketItem item : state.ticket.items) {
            sb.append(formatLine(item.label, money(item.getPriceFormatted())));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (state.ticket.globalDiscountApplied != null) {
            sb.append(formatLine(i18n.get("paper.remiseTicket"),
                    "-" + money(DF.format(state.ticket.globalDiscountApplied))));
        }
        sb.append(formatLine(i18n.get("paper.total"), money(state.ticket.getTotalFormatted())));
        for (com.intermarche.pos.ui.payment.PaymentState.PaymentEntry entry : state.payment.payments) {
            sb.append(formatLine("  " + entry.method, money(DF.format(entry.amount))));
        }
        sb.append("\n").append(center(i18n.get("paper.formationSansValeur"), WIDTH)).append("\n\n");
        return sb.toString();
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
        String validityLine = validUntil == null ? "" : String.format(i18n.get("paper.valableJusqu"),
                validUntil.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        String sb = brandBanner() +
                center(documentTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER,
                        i18n.both("paper.avoir")), WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine(i18n.both("paper.montant"), money(DF.format(refund.totalAmount))) +
                String.format(i18n.get("paper.emis"),
                        refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))) +
                validityLine +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                center(barcode(number), WIDTH) + "\n" +
                center(i18n.both("paper.scannableCaisseSoldeRegistre"), WIDTH) + "\n" +
                "\n";
        printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CREDIT, number, sb, PrintPolicy.DOCUMENT_CREDIT_NOTE, true);
        LOGGER.info("Exiting method printRefundVoucher");
    }

    /**
     * Prints the paper the customer signs to authorize a loyalty debit
     * (LC-07-10-05), before the settlement is registered.
     * @param amount the reserved loyalty amount
     * @param card the loyalty identity shown on the current sale
     */
    public void printFidelitySignatureSlip(BigDecimal amount, String card) {
        String body = brandBanner()
                + center(i18n.both("pay.loyaltySignature"), WIDTH) + "\n"
                + String.valueOf(card) + "\n"
                + money(DF.format(amount)) + "\n"
                + i18n.both("pay.customerSignature") + "\n\n\n"
                + "_".repeat(WIDTH) + "\n";
        hardwareService.printReceipt(body);
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
        LOGGER.info("Entering method printLoyaltyCredit with amount: " + amount);
        String sb = brandBanner() +
                center(i18n.both("paper.remboursementCagnotte"), WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine(i18n.both("paper.creditCarte"), "+" + money(DF.format(amount))) +
                center(i18n.both("paper.visibleCarteSousQuelques"), WIDTH) + "\n" +
                "\n";
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.LOYALTY, amount, sb);
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
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER, number, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printGiftCardVoucher");
            return;
        }
        String sb = center(i18n.get("paper.intermarche"), WIDTH) + "\n" +
                center(documentTitle(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER,
                        i18n.both("paper.carteCadeau")), WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine(i18n.both("paper.montantCharge"), money(DF.format(amount))) +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                // BO-03-06-30 to -38: the code, composed or not, is printed scannable.
                center(barcode(number), WIDTH) + "\n" +
                center(i18n.both("paper.scannableCaisseSoldeRegistre"), WIDTH) + "\n" +
                "\n";
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER, number, sb);
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
        // BO-03-03-08: the end of validity the registry holds, when the shop
        // states it on the paper.
        java.time.LocalDateTime validUntil = creditNoteExpiry(number);
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER,
                instrumentDocumentData(number, amount, validUntil));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER, number, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printChangeVoucher");
            return;
        }
        String expiry = validUntil == null ? ""
                : center(i18n.format("paper.valableJusquau",
                        validUntil.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                        WIDTH) + "\n";
        String sb = center(i18n.get("paper.intermarche"), WIDTH) + "\n" +
                center(i18n.both("paper.avoirRenduMonnaie"), WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine(i18n.both("paper.montant"), money(DF.format(amount))) +
                "\n" + center("N° " + number, WIDTH) + "\n" +
                // BO-03-06-30 to -38: the code, composed or not, is printed scannable.
                center(barcode(number), WIDTH) + "\n" +
                expiry +
                center(i18n.both("paper.scannableCaisseSoldeRegistre"), WIDTH) + "\n" +
                "\n";
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER, number, sb);
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
            case CASH -> i18n.get("paper.especes");
            case CARD -> i18n.get("paper.carteBancaire");
            case VOUCHER -> i18n.get("paper.bonAchat");
            case LOYALTY -> i18n.get("paper.cagnotte2");
            case CREDIT -> i18n.get("paper.creditClient");
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
     * <p>A BILINGUAL MENTION TOO WIDE FOR THE PAPER (LC-13-06-11) — its two
     * renderings joined by " / " — is centred on two lines, the second
     * language on the first one, rather than running past the paper's last
     * column. Every text that fits is centred exactly as before.
     *
     * @param text the text to centre
     * @param width the width to centre on
     * @return the padded text, on two lines for a bilingual mention too wide,
     *         without its last terminator
     */
    private String center(String text, int width) {
        com.intermarche.pos.service.DocumentLayout layout = new com.intermarche.pos.service.DocumentLayout(width);
        int separator = text == null ? -1 : text.indexOf(" / ");
        if (separator > 0 && text.length() > width) {
            return layout.centre(text.substring(0, separator + 2)) + "\n"
                    + layout.centre(text.substring(separator + 3));
        }
        return layout.centre(text);
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
     * Lays out one row of the per-rate VAT ventilation: the rate on the left,
     * the base and the tax on the right.
     *
     * <p>ONE LINE WHEN IT FITS, which is every monolingual ticket, printed
     * character for character as before. A bilingual store (LC-13-06-11)
     * doubles every label of the row, and the row no longer holds on the
     * paper's columns: it then takes two lines — the rate, then the base and
     * the tax aligned on the right — with the padding the labels carry for the
     * one-line form collapsed, so nothing runs past the paper's width.
     *
     * @param rate the rate as printed, its VAT letter first where the store prints one
     * @param bucket the rate's totals
     * @return the row, one or two lines, each ending with a line feed
     */
    private String vatVentilationLine(String rate, VatBreakdown.Bucket bucket) {
        String left = i18n.both("paper.tva") + rate;
        String right = i18n.both("paper.ht") + DF.format(bucket.totalExcludingTax)
                + i18n.both("paper.tva") + DF.format(bucket.vatAmount);
        if (left.length() + 1 + right.length() <= WIDTH) {
            return formatLine(left, right);
        }
        return "  " + squeeze(i18n.both("paper.tva")) + " " + rate + "\n"
                + formatLine("", squeeze(i18n.both("paper.ht")) + " " + DF.format(bucket.totalExcludingTax)
                        + "  " + squeeze(i18n.both("paper.tva")) + " " + DF.format(bucket.vatAmount));
    }

    /**
     * Collapses the padding a paper label carries for the one-line layout.
     *
     * @param label the label, possibly bilingual
     * @return the label trimmed, its runs of spaces reduced to one
     */
    private static String squeeze(String label) {
        return label.trim().replaceAll(" {2,}", " ");
    }
    /**
     * Prints the operator badge as CODE128 through the hardware bridge
     * (LC-01-06-01), whose human-readable caption also allows manual entry.
     *
     * @param employee the operator whose badge is reprinted
     */
    public void printOperatorBadge(com.intermarche.pos.domain.people.Employee employee) {
        LOGGER.info("Entering method printOperatorBadge with employee: " + employee);
        String sb = logoHeader() + center(i18n.get("paper.badgeOperateur"), WIDTH) + "\n" +
                "-".repeat(WIDTH) + "\n" +
                formatLine(i18n.get("paper.operateur"), employee.loginName) +
                (employee.badgeId != null ? barcode(employee.badgeId) + "\n" : formatLine(i18n.get("paper.badge"), "-")) +
                "-".repeat(WIDTH) + "\n" +
                center(i18n.get("paper.scannezSaisissezNumero"), WIDTH) + "\n";
        hardwareService.printReceipt(sb);
        LOGGER.info("Exiting method printOperatorBadge");
    }
    /**
     * LC-04-01-02: print the exact resume identity as CODE128 and clear text,
     * with the live article detail only when administered.
     *
     * <p>BO-03-03-06: the store's own layout first, the {@code PARKED_TICKET}
     * template reading the title, the barcode, the number in clear, the moment
     * of printing and the articles; none administered, the register's own
     * slip below, which states the same five values.
     *
     * @param draft the still-parked local ticket
     */
    public void printParkedTicket(com.intermarche.pos.domain.sale.Ticket draft) {
        LOGGER.info("Entering method printParkedTicket with draft: " + draft);
        String printedAt = com.intermarche.pos.domain.util.DateTimeProvider.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.PARKED_TICKET,
                parkedDocumentData(draft, printedAt));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.PARKED, draft.ticketNumber, administered);
            hardwareService.printReceipt(administered);
            LOGGER.info("Exiting method printParkedTicket");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(parkedTitle(), WIDTH)).append("\n");
        sb.append(center(draft.ticketNumber, WIDTH)).append("\n");
        sb.append(barcode(draft.ticketNumber)).append("\n");
        sb.append(String.format(i18n.get("paper.date"), printedAt));
        sb.append("-".repeat(WIDTH)).append("\n");
        if (posSettingsService.parkingReceiptDetailed()) {
            for (TicketLine line : draft.lines) {
                // A cancelled article (lot C4) prints on the parked receipt only
                // where the shop asks for it (BO-10-08-30).
                if (line.cancelled) {
                    if (posSettingsService.cancelledLinesPrinted()) {
                        sb.append(cancelledLine(line));
                    }
                    continue;
                }
                String label = line.productLabel.length() > 20
                        ? line.productLabel.substring(0, 20) : line.productLabel;
                sb.append(formatLine(label + " x" + DF.format(line.quantity),
                        money(DF.format(line.totalPrice))));
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.totalAttente"), money(DF.format(draft.totalIncludingTax))));
        sb.append(center(i18n.get("paper.scannezNumeroReprendre"), WIDTH)).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.PARKED, draft.ticketNumber, sb.toString());
        hardwareService.printReceipt(sb.toString());
        LOGGER.info("Exiting method printParkedTicket");
    }

    /**
     * The NAME the parked slip carries, administered or the register's own
     * (BO-03-03-06, BO-03-04-12).
     *
     * <p>The referential is read only where there is one: a printer built by
     * hand reaches no database, and prints the register's own wording.
     *
     * @return the title to print, never null
     */
    private String parkedTitle() {
        String builtIn = i18n.get("paper.ticketAttente");
        if (documentTemplateService == null) {
            return builtIn;
        }
        return documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.PARKED_TICKET, builtIn);
    }

    /**
     * Builds the PARKED ticket as an administered layout sees it (BO-03-03-06).
     *
     * <p>The articles are handed over only where the shop details the parked
     * slip, exactly as the built-in slip prints them, and never the cancelled
     * ones: a layout states what the register would have stated.
     *
     * @param draft the still-parked ticket
     * @param printedAt the moment of printing, already written
     * @return the document values
     */
    java.util.Map<String, Object> parkedDocumentData(com.intermarche.pos.domain.sale.Ticket draft,
                                                     String printedAt) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", storeData(draft));
        data.put("terminal", safe(draft.terminalId));
        data.put("operator", draft.cashier == null ? "" : safe(draft.cashier.getFullName()));
        data.put("date", printedAt.substring(0, 10));
        data.put("time", printedAt.substring(11));
        String number = safe(draft.ticketNumber);
        data.put("document", java.util.Map.of(
                "title", parkedTitle(),
                "number", number,
                "barcode", number.isEmpty() ? "" : barcode(number),
                "barcodeNumber", number,
                "printDate", printedAt));
        java.util.List<java.util.Map<String, Object>> lines = new java.util.ArrayList<>();
        if (posSettingsService.parkingReceiptDetailed() && draft.lines != null) {
            for (TicketLine line : draft.lines) {
                if (line.cancelled) {
                    continue;
                }
                lines.add(java.util.Map.of(
                        "label", safe(line.productLabel),
                        "quantity", line.quantity == null ? "" : DF.format(line.quantity),
                        "total", line.totalPrice == null ? "" : DF.format(line.totalPrice)));
            }
        }
        data.put("lines", lines);
        data.put("totals", java.util.Map.of("includingTax", draft.totalIncludingTax == null
                ? "" : DF.format(draft.totalIncludingTax)));
        return data;
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
        String sb = center(i18n.both("paper.identifiantTicket"), WIDTH) + "\n" +
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
            // LC-08-05-07: where the integrated monetique composed the receipt,
            // that very paper is reprinted, under the mention — not a slip the
            // register rebuilds from the few traces it holds.
            if (card.customerReceipt != null) {
                String frame = mention == null ? card.customerReceipt
                        : center("*** " + mention + " ***", WIDTH) + "\n" + card.customerReceipt;
                archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, ticket.ticketNumber, frame);
                hardwareService.printReceipt(frame);
                hardwareService.cutPaper();
                printed++;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            cardHeader(sb, ticket.store != null ? ticket.store.name : null, mention);
            sb.append(String.format(i18n.get("paper.ticket2"), ticket.ticketNumber));
            sb.append(String.format(i18n.get("paper.date"),
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(card.amount))));
            if (card.authorizationNumber != null && !card.authorizationNumber.isBlank()) {
                sb.append(formatLine(i18n.both("paper.autorisation"), card.authorizationNumber));
            }
            if (card.degradedMode) {
                sb.append(center(i18n.both("paper.modeDegrade"), WIDTH)).append("\n");
            }
            if (signatureRequired) {
                sb.append("\n").append(center(i18n.both("paper.signatureClient"), WIDTH)).append("\n\n\n");
            }
            sb.append("\n");
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, ticket.ticketNumber, sb.toString());
            hardwareService.printReceipt(sb.toString());
            hardwareService.cutPaper();
            printed++;
        }
        LOGGER.info("Exiting method printCardReceipt");
        return printed;
    }

    /** Sends the LC-07-04-10 vendor frame unchanged, without reformatting or journal storage.
     * @param frame exact vendor frame, including its own line endings
     */
    public void printTerminalFrame(String frame) {
        printTerminalFrame(frame, null);
    }

    /**
     * Sends a vendor frame unchanged, under the administered EFT logos
     * (BO-10-07-13): the monetique logo above every terminal receipt and, on
     * a contactless payment, the contactless logo the law requires.
     *
     * <p>The frame itself is never reformatted: the images are printed above
     * it, as the store logo is above a receipt, and a shop that administered
     * none — or disabled them — prints the frame alone, as before.
     *
     * @param frame exact vendor frame, including its own line endings
     * @param entryMode the card entry mode the terminal reported, or null
     */
    public void printTerminalFrame(String frame, String entryMode) {
        hardwareService.printReceipt(eftLogos("SANS_CONTACT".equals(entryMode)) + frame);
        hardwareService.cutPaper();
    }

    /**
     * The image directives printed above a terminal receipt (BO-10-07-13).
     *
     * @param contactless whether the payment was contactless
     * @return the directives, each with its newline, or an empty string
     */
    private String eftLogos(boolean contactless) {
        if (storeImageService == null || !posSettingsService.eftLogosPrinted()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendImage(sb, storeImageService.eftLogo());
        if (contactless) {
            appendImage(sb, storeImageService.contactlessLogo());
        }
        return sb.toString();
    }

    /**
     * Appends the print directive of an administered image, nothing when the
     * image is absent or cannot be rasterised.
     *
     * @param sb the receipt buffer
     * @param picture the image data URI, possibly empty
     */
    private void appendImage(StringBuilder sb, String picture) {
        if (picture.isEmpty()) {
            return;
        }
        String directive = ReceiptLogo.directive(picture, posSettingsService.logoWidthDots());
        if (!directive.isEmpty()) {
            sb.append(directive).append("\n");
        }
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
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.LOYALTY, ticketId, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printLoyaltyReceipt");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.paiementFidelite"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket2"), safe(ticket.ticketNumber)));
        if (ticket.terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format(i18n.get("paper.date"),
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.paiementTotalFid"), money(DF.format(used))));
        sb.append(formatLine(i18n.both("paper.carte"), safe(ticket.fidelityCard)));
        if (ticket.fidelityAvailableBalance != null) {
            sb.append(formatLine(i18n.both("paper.soldeAvant"), money(DF.format(ticket.fidelityAvailableBalance))));
        }
        // imfid response B: the ledger balance after the burn, read from the
        // confirmation, never recomputed by the register.
        if (ticket.fidelityBalanceAfter != null) {
            sb.append(formatLine(i18n.both("paper.soldeApres"), money(DF.format(ticket.fidelityBalanceAfter))));
        }
        sb.append("\n").append(center(i18n.both("paper.signatureClient"), WIDTH)).append("\n\n\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.LOYALTY, ticketId, sb.toString());
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
     * The LEGAL MENTIONS the regimes of a document oblige it to carry
     * ({@code BO-02-02-02}), each on its own wrapped lines.
     *
     * <p>ONE MENTION, ONCE. Two regimes of the same country usually name the
     * same sentence, and a receipt printing it twice would read as a defect
     * rather than as a legal statement; the set keeps the first occurrence and
     * the order of the ventilation.
     *
     * <p>Wrapped and never truncated: a legal sentence cut in half says
     * nothing at all, which is the rule every administered text on this paper
     * follows. Nothing administered yields an empty string, and the document
     * is byte-for-byte the one printed before the mentions existed.
     *
     * @param breakdown the ventilation of the document
     * @return the mentions and their newlines, or an empty string
     */
    String vatLegalMentions(VatBreakdown breakdown) {
        StringBuilder printed = new StringBuilder();
        for (String mention : vatMentionList(breakdown)) {
            for (String line : wrap(mention, WIDTH)) {
                printed.append(line).append("\n");
            }
        }
        return printed.toString();
    }

    /**
     * The distinct legal mentions the regimes of a ventilation oblige
     * ({@code BO-02-02-02}), in the order of the ventilation, each once.
     *
     * @param breakdown the ventilation of the document
     * @return the mentions, empty when no regime obliges one
     */
    java.util.List<String> vatMentionList(VatBreakdown breakdown) {
        java.util.LinkedHashSet<String> mentions = new java.util.LinkedHashSet<>();
        for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
            String mention = mentionOf(bucket);
            if (mention != null) {
                mentions.add(mention);
            }
        }
        return new java.util.ArrayList<>(mentions);
    }

    /**
     * The legal mention the regime of one bucket obliges ({@code BO-02-02-02}),
     * the regime read by its number when the bucket names one
     * ({@code BO-02-02-01}).
     *
     * @param bucket the ventilation bucket
     * @return the trimmed mention, or null when the regime obliges none
     */
    String mentionOf(VatBreakdown.Bucket bucket) {
        com.intermarche.pos.domain.catalog.VatRate regime = regimeOf(bucket.rate, bucket.number);
        if (regime == null || regime.legalMention == null || regime.legalMention.isBlank()) {
            return null;
        }
        return regime.legalMention.trim();
    }

    /**
     * The legal mentions of a sale, for an administered layout
     * ({@code BO-02-02-02}): the same list the built-in receipt prints under
     * its ventilation.
     *
     * @param ticket the sale
     * @return the mentions, empty when none is owed
     */
    java.util.List<String> saleVatMentions(Ticket ticket) {
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        if (ticket.lines != null) {
            for (TicketLine line : ticket.lines) {
                if (!line.cancelled) {
                    breakdown.add(line.vatRate, line.vatNumber, line.totalPrice);
                }
            }
        }
        return vatMentionList(breakdown);
    }

    /**
     * The regime number of the sold line a refund line gives back
     * ({@code BO-02-02-01}), so the return document names the regime of the
     * sale rather than the first regime of its rate.
     *
     * @param line the refund line
     * @return the number, or null when the original line is unknown or names none
     */
    private Integer regimeOfOriginal(RefundLine line) {
        TicketLine original = line.originalLineId == null ? null : TicketLine.findById(line.originalLineId);
        return original == null ? null : original.vatNumber;
    }

    /**
     * The NUMBER of the VAT regime a sold line was taxed under (BO-03-03-37).
     *
     * <p>Read back from the RATE the line snapshotted, because the number lives
     * on the regime and the line keeps the rate: that is what makes a reprint
     * state the regime of the sale rather than the regime of today.
     *
     * @param line the sold line
     * @return the alphabetic regime code, or null when the line carries no rate
     *         or no regime carries that rate
     */
    private String vatCodeOf(TicketLine line) {
        return vatLetter(line.vatRate, line.vatNumber);
    }

    /**
     * Prints the EXIT ticket of a closed sale, INSTEAD OF the sale receipt
     * (BO-03-03-36).
     *
     * <p>Substitutive and not additional: a store that administers it hands the
     * customer the few figures a gate or a greeter checks — a total, a count of
     * articles and the dearest ones named — instead of eighty lines nobody
     * reads on the way out. The caller does not have to know whether the store
     * administers it: a zero return means "nothing printed, print the receipt".
     *
     * <p>Every value is DERIVED from the sale. Nothing new is stored, and a
     * store that switches it off goes back to the receipt with no trace left.
     *
     * @param ticketId the database id of the closed ticket
     * @return the number of exit tickets printed — zero when the ticket is
     *         unknown or the store does not administer the document
     */
    @Transactional
    public int printExitTicket(Long ticketId) {
        LOGGER.info("Entering method printExitTicket with ticketId: " + ticketId);
        if (!posSettingsService.exitTicketEnabled()) {
            LOGGER.info("Exiting method printExitTicket");
            return 0;
        }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printExitTicket");
            return 0;
        }
        java.util.List<TicketLine> dearest = dearestLines(ticket);
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.EXIT_TICKET,
                exitDocumentData(ticket, dearest));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.EXIT, ticketId, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printExitTicket");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.EXIT_TICKET,
                i18n.both("paper.ticketSortie")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.totalTtc"),
                money(DF.format(ticket.totalIncludingTax == null
                        ? BigDecimal.ZERO : ticket.totalIncludingTax))));
        sb.append(formatLine(i18n.both("paper.nombreArticles"),
                String.valueOf(ticket.itemCount)));
        sb.append("-".repeat(WIDTH)).append("\n");
        for (TicketLine line : dearest) {
            String label = line.productLabel == null ? ""
                    : (line.productLabel.length() > 20
                            ? line.productLabel.substring(0, 20) : line.productLabel);
            sb.append(formatLine(label + " x" + DF.format(line.quantity),
                    money(DF.format(line.totalPrice))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(safe(ticket.ticketNumber), WIDTH)).append("\n");
        sb.append(barcode(safe(ticket.ticketNumber))).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.EXIT, ticketId, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printExitTicket");
        return 1;
    }

    /**
     * The dearest lines of a sale, the most expensive first (BO-03-03-36).
     *
     * <p>Cancelled lines are not sold, so they are not candidates; the store
     * says how many are named, and a sale with fewer lines than that names all
     * of them.
     *
     * @param ticket the closed sale
     * @return the lines to name, never null
     */
    private java.util.List<TicketLine> dearestLines(Ticket ticket) {
        java.util.List<TicketLine> lines = new java.util.ArrayList<>();
        if (ticket.lines != null) {
            for (TicketLine line : ticket.lines) {
                if (!line.cancelled) {
                    lines.add(line);
                }
            }
        }
        lines.sort((left, right) -> {
            BigDecimal leftTotal = left.totalPrice == null ? BigDecimal.ZERO : left.totalPrice;
            BigDecimal rightTotal = right.totalPrice == null ? BigDecimal.ZERO : right.totalPrice;
            return rightTotal.compareTo(leftTotal);
        });
        int kept = posSettingsService.exitTicketArticles();
        return lines.size() <= kept ? lines : new java.util.ArrayList<>(lines.subList(0, kept));
    }

    /**
     * Builds the exit ticket as an administered layout sees it (BO-03-03-36).
     *
     * @param ticket the closed sale
     * @param dearest the lines named on the document, dearest first
     * @return the document values, every figure already formatted
     */
    java.util.Map<String, Object> exitDocumentData(Ticket ticket,
            java.util.List<TicketLine> dearest) {
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
                // The barcode as the DIRECTIVE the hardware bridge encodes, like
                // the credit note's: the paper a gate reads is scanned, not typed.
                "barcode", barcode(safe(ticket.ticketNumber))));
        data.put("totals", java.util.Map.of("includingTax",
                DF.format(ticket.totalIncludingTax == null
                        ? BigDecimal.ZERO : ticket.totalIncludingTax)));
        data.put("articleCount", String.valueOf(ticket.itemCount));
        java.util.List<java.util.Map<String, Object>> topLines = new java.util.ArrayList<>();
        for (TicketLine line : dearest) {
            topLines.add(java.util.Map.of(
                    "label", line.productLabel == null ? "" : line.productLabel,
                    "quantity", DF.format(line.quantity),
                    "unitPrice", line.unitPrice == null ? "" : DF.format(line.unitPrice),
                    "total", line.totalPrice == null ? "" : DF.format(line.totalPrice)));
        }
        data.put("topLines", topLines);
        return data;
    }

    /**
     * Prints the RESUME ticket of a parked sale that came back (BO-03-03-07).
     *
     * <p>The parked receipt said what was put aside; this one says that it came
     * back, and names BOTH numbers — the ticket the sale goes on with and the
     * number the parked paper carried. They are the same on an ordinary resume
     * and differ on one taken over from another register (EXP-46), and that
     * difference is what a dispute two hours later turns on.
     *
     * @param ticket the resumed draft
     * @param originalNumber the number the parked receipt carried
     * @return the number of documents printed — zero when the store does not
     *         administer the document or the draft is unknown
     */
    public int printResumeTicket(Ticket ticket, String originalNumber) {
        LOGGER.info("Entering method printResumeTicket with originalNumber: " + originalNumber);
        if (!posSettingsService.parkingPrintResume() || ticket == null) {
            LOGGER.info("Exiting method printResumeTicket");
            return 0;
        }
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .PARKED_TICKET_RESUME,
                resumeDocumentData(ticket, originalNumber));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.RESUME, originalNumber, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printResumeTicket");
            return 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .PARKED_TICKET_RESUME,
                i18n.both("paper.ticketReprise")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        // The PRINTING date, which the questionnaire asks for by that name: the
        // draft was created before it was parked, and what this paper dates is
        // the moment it came back.
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(String.format(i18n.get("paper.ticket2"), safe(ticket.ticketNumber)));
        sb.append(String.format(i18n.get("paper.ticketOriginal"), safe(originalNumber)));
        sb.append("-".repeat(WIDTH)).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.RESUME, originalNumber, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printResumeTicket");
        return 1;
    }

    /**
     * Builds the resume ticket as an administered layout sees it (BO-03-03-07).
     *
     * @param ticket the resumed draft
     * @param originalNumber the number the parked receipt carried
     * @return the document values, every figure already formatted
     */
    java.util.Map<String, Object> resumeDocumentData(Ticket ticket, String originalNumber) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("store", storeData(ticket));
        data.put("terminal", safe(ticket.terminalId));
        data.put("operator", ticket.cashier == null ? "" : safe(ticket.cashier.getFullName()));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        data.put("date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(ticket.ticketNumber),
                "originalNumber", safe(originalNumber)));
        return data;
    }

    /**
     * Prints the UNKNOWN-ARTICLE slip of a code nothing recognized
     * (BO-03-10-05).
     *
     * <p>The paper a department manager walks back to the referential with: the
     * code as it was read, the register and the moment. The screen already
     * carries the message — this is not a second way of saying it, it is the
     * trace that leaves the till.
     *
     * <p>Administered per ISLAND, and the caller does not have to know: a zero
     * return means the island asks for no paper.
     *
     * @param code the code that was read, possibly null
     * @return the number of slips printed — zero when the island asks for none
     *         or the code is blank
     */
    public int printUnknownArticleTicket(String code) {
        LOGGER.info("Entering method printUnknownArticleTicket with code: " + code);
        if (!posSettingsService.islandUnknownArticleTicket() || code == null || code.isBlank()) {
            LOGGER.info("Exiting method printUnknownArticleTicket");
            return 0;
        }
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .UNKNOWN_ARTICLE_TICKET,
                unknownArticleDocumentData(code));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printUnknownArticleTicket");
            return 1;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .UNKNOWN_ARTICLE_TICKET,
                i18n.both("paper.articleInconnu")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.codeLu"), code.trim()));
        // BO-10-07-11: the same code as a BARCODE, so the department rescans it
        // at the referential instead of keying thirteen digits again. The
        // directive is what the hardware bridge encodes; a code it cannot encode
        // prints as the directive line, which is visible rather than missing.
        if (posSettingsService.unknownArticleBarcodePrinted()) {
            sb.append(barcode(code.trim())).append("\n");
        }
        if (ticketNumberService != null) {
            sb.append(String.format(i18n.get("paper.caisse2"),
                    safe(ticketNumberService.getTerminalId())));
        }
        sb.append(String.format(i18n.get("paper.date"),
                now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printUnknownArticleTicket");
        return 1;
    }

    /**
     * Builds the unknown-article slip as an administered layout sees it
     * (BO-03-10-05).
     *
     * @param code the code that was read
     * @return the document values
     */
    java.util.Map<String, Object> unknownArticleDocumentData(String code) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        data.put("terminal", ticketNumberService == null
                ? "" : safe(ticketNumberService.getTerminalId()));
        data.put("operator", "");
        data.put("date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        // BO-10-07-11: the administered layout receives the directive too, so a
        // store template can place the barcode where it wants it.
        data.put("document", java.util.Map.of("code", code.trim(),
                "barcode", posSettingsService.unknownArticleBarcodePrinted()
                        ? barcode(code.trim()) : ""));
        return data;
    }

    /**
     * Prints the slip of a CANCELLED settlement (BO-03-02-35).
     *
     * <p>A settlement undone is an event and not a non-event: the customer
     * handed over a cheque or a card and the register gave it back, and the
     * paper says which tender, for how much, on which ticket and when. It goes
     * through the SAME engine as every other document — an administered layout
     * wins, the built-in one is the fallback — because the engine exists; the
     * editor comment claiming it had to be created first was written before it
     * did.
     *
     * <p>Nothing is printed when the tender is not named or the amount is not
     * positive: a slip saying a settlement of nothing was cancelled would only
     * confuse the till's journal.
     *
     * @param ticketNumber the number of the ticket the settlement was on, or null
     * @param tenderLabel the wording of the cancelled tender, or null
     * @param amount the amount that was given back, or null
     * @return 1 when a slip was printed, 0 when there was nothing to print
     */
    public int printPaymentCancellation(String ticketNumber, String tenderLabel,
                                        BigDecimal amount) {
        LOGGER.info("Entering method printPaymentCancellation with ticketNumber: "
                + ticketNumber + ", tenderLabel: " + tenderLabel + ", amount: " + amount);
        if (tenderLabel == null || tenderLabel.isBlank()
                || amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method printPaymentCancellation");
            return 0;
        }
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .PAYMENT_CANCELLATION,
                paymentCancellationDocumentData(ticketNumber, tenderLabel, amount));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.PAYMENT_CANCELLATION, ticketNumber, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printPaymentCancellation");
            return 1;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType
                        .PAYMENT_CANCELLATION,
                i18n.both("paper.annulationReglement")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (ticketNumberService != null) {
            sb.append(String.format(i18n.get("paper.caisse2"),
                    safe(ticketNumberService.getTerminalId())));
        }
        sb.append(String.format(i18n.get("paper.date"),
                now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(i18n.both("paper.ticketNumero"), safe(ticketNumber)));
        sb.append(formatLine(i18n.both("paper.reglement"), tenderLabel.trim()));
        sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(amount))));
        sb.append("-".repeat(WIDTH)).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.PAYMENT_CANCELLATION, ticketNumber, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printPaymentCancellation");
        return 1;
    }

    /**
     * Prints the DEPOSIT slip of a customer order (BO-03-03-09).
     *
     * <p>The money is taken before the goods exist, so this paper is the
     * customer's only proof. It names the order, the customer, the amount and a
     * unique number, and carries that number as a BARCODE so the balance can be
     * settled by scanning it rather than by typing it back.
     *
     * <p>The VAT recap is handed in rather than computed: a deposit's breakdown
     * belongs to the order it settles, and this printer does not query. Absent,
     * the block simply does not print.
     *
     * <p>Nothing is printed without a unique number or without a positive
     * amount: a proof nobody can look up is not a proof.
     *
     * @param uniqueNumber the slip's own number, barcoded, or null
     * @param orderNumber the order the deposit settles, or null
     * @param customerName the customer's name, or null
     * @param amount the amount taken, or null
     * @param vatLines the VAT recap rows, each carrying a rate, a base and an
     *        amount, or null when the caller has none
     * @return 1 when a slip was printed, 0 when there was nothing to print
     */
    public int printDepositReceipt(String uniqueNumber, String orderNumber,
                                   String customerName, BigDecimal amount,
                                   java.util.List<java.util.Map<String, String>> vatLines) {
        LOGGER.info("Entering method printDepositReceipt with uniqueNumber: " + uniqueNumber
                + ", orderNumber: " + orderNumber + ", amount: " + amount);
        if (uniqueNumber == null || uniqueNumber.isBlank()
                || amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method printDepositReceipt");
            return 0;
        }
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.DEPOSIT_RECEIPT,
                depositDocumentData(uniqueNumber, orderNumber, customerName, amount, vatLines));
        if (administered != null) {
            int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT, uniqueNumber, administered, PrintPolicy.DOCUMENT_DEPOSIT, true);
            LOGGER.info("Exiting method printDepositReceipt");
            return printed;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.DEPOSIT_RECEIPT,
                i18n.both("paper.acompte")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (ticketNumberService != null) {
            sb.append(String.format(i18n.get("paper.caisse2"),
                    safe(ticketNumberService.getTerminalId())));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(i18n.both("paper.acompteClient"), safe(customerName)));
        sb.append(formatLine(i18n.both("paper.acompteCommande"), safe(orderNumber)));
        sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(amount))));
        if (vatLines != null && !vatLines.isEmpty()) {
            sb.append("-".repeat(WIDTH)).append("\n");
            for (java.util.Map<String, String> vat : vatLines) {
                if (vat == null) {
                    continue;
                }
                sb.append(formatLine(safe(vat.get("rate")), safe(vat.get("amount"))));
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(uniqueNumber.trim(), WIDTH)).append("\n");
        sb.append(barcode(uniqueNumber.trim())).append("\n");
        int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT, uniqueNumber, sb.toString(), PrintPolicy.DOCUMENT_DEPOSIT, true);
        LOGGER.info("Exiting method printDepositReceipt");
        return printed;
    }

    /**
     * Builds the deposit slip as an administered layout sees it (BO-03-03-09).
     *
     * @param uniqueNumber the slip's own number
     * @param orderNumber the order the deposit settles, or null
     * @param customerName the customer's name, or null
     * @param amount the amount taken
     * @param vatLines the VAT recap rows, or null
     * @return the document values
     */
    java.util.Map<String, Object> depositDocumentData(String uniqueNumber, String orderNumber,
                                                      String customerName, BigDecimal amount,
                                                      java.util.List<java.util.Map<String, String>> vatLines) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        data.put("terminal", ticketNumberService == null
                ? "" : safe(ticketNumberService.getTerminalId()));
        data.put("operator", "");
        data.put("date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", uniqueNumber.trim(),
                "orderNumber", safe(orderNumber),
                "customerName", safe(customerName),
                "amount", DF.format(amount),
                "barcode", barcode(uniqueNumber.trim())));
        data.put("vatLines", vatLines == null
                ? java.util.List.of()
                : vatLines.stream().filter(java.util.Objects::nonNull).toList());
        return data;
    }

    /**
     * Prints the deposit ticket of an order CREATED at the till
     * (LC-06-03-06).
     *
     * <p>The customer walks away with the paper that brings them back: the
     * order's articles, the deposit they paid, the VAT recap computed on that
     * deposit (LC-06-03-05) and the BARCODE of the order code — the one thing
     * that reopens the order at any register of the store (LC-06-03-07).
     *
     * <p>Nothing is printed without an order code or without a positive
     * deposit: a slip that names no order reopens nothing, and a slip that
     * states no money proves nothing.
     *
     * @param orderCode the code of the order, barcoded, or null
     * @param lines the order's article lines, possibly empty
     * @param deposit the deposit amount collected, or null
     * @param vatDetail the per-rate VAT of the deposit, in the compact
     *        {@code DepositVat} form, or null when it ventilated to nothing
     * @return 1 when a ticket was printed, 0 when there was nothing to print
     */
    public int printOrderDepositTicket(String orderCode,
                                       java.util.List<com.intermarche.pos.ui.recall.RecalledDocument.Line> lines,
                                        BigDecimal deposit, String vatDetail) {
        return printOrderDepositTicket(com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE,
                orderCode, lines, deposit, vatDetail);
    }

    /**
     * Prints the deposit ticket of a document — an order created at the till
     * (LC-06-03-06), or a quote or an order the commercial management tool issued
     * (LC-06-02-04) — naming the document by its kind.
     *
     * @param kind the kind of the document the deposit was taken on; null reads
     *        as an order
     * @param orderCode the code of the document, barcoded, or null
     * @param lines the document's article lines, possibly empty
     * @param deposit the deposit amount collected, or null
     * @param vatDetail the per-rate VAT of the deposit, in the compact
     *        {@code DepositVat} form, or null when it ventilated to nothing
     * @return 1 when a ticket was printed, 0 when there was nothing to print
     */
    public int printOrderDepositTicket(com.intermarche.pos.ui.recall.RecalledDocument.Kind kind,
                                       String orderCode,
                                       java.util.List<com.intermarche.pos.ui.recall.RecalledDocument.Line> lines,
                                       BigDecimal deposit, String vatDetail) {
        return printOrderDepositTicket(kind, orderCode, lines, deposit, vatDetail, null);
    }

    /**
     * Prints the deposit ticket of a document, naming the customer it was taken
     * from (BO-03-03-09).
     *
     * <p>The store's DEPOSIT_RECEIPT layout first: the title, the barcode and
     * its number, the customer, the order, the amount, the unique number — the
     * order's code, which reopens it — and the VAT recap of the deposit rate by
     * rate, with its code, base, tax and total. None administered, the
     * register's own slip below states the same values. Either way it is an
     * associated document of the sale, forced by the {@code ACOMPTE} key under
     * conditional printing (BO-03-03-01).
     *
     * @param kind the kind of the document the deposit was taken on; null reads
     *        as an order
     * @param orderCode the code of the document, barcoded, or null
     * @param lines the document's article lines, possibly empty
     * @param deposit the deposit amount collected, or null
     * @param vatDetail the per-rate VAT of the deposit, in the compact
     *        {@code DepositVat} form, or null when it ventilated to nothing
     * @param customerName the customer the deposit was taken from, or null
     * @return 1 when a ticket was printed, 0 when there was nothing to print
     */
    public int printOrderDepositTicket(com.intermarche.pos.ui.recall.RecalledDocument.Kind kind,
                                       String orderCode,
                                       java.util.List<com.intermarche.pos.ui.recall.RecalledDocument.Line> lines,
                                       BigDecimal deposit, String vatDetail, String customerName) {
        LOGGER.info("Entering method printOrderDepositTicket with orderCode: " + orderCode
                + ", deposit: " + deposit);
        if (orderCode == null || orderCode.isBlank()
                || deposit == null || deposit.signum() <= 0) {
            LOGGER.info("Exiting method printOrderDepositTicket (nothing to print)");
            return 0;
        }
        java.util.List<java.util.Map<String, String>> recap = depositVatRecap(vatDetail);
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.DEPOSIT_RECEIPT,
                depositDocumentData(orderCode, orderCode, customerName, deposit, recap));
        if (administered != null) {
            int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT,
                    orderCode, administered, PrintPolicy.DOCUMENT_DEPOSIT, true);
            LOGGER.info("Exiting method printOrderDepositTicket (administered)");
            return printed;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(i18n.both("paper.acompte"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.caisse2"),
                safe(ticketNumberService == null ? "" : ticketNumberService.getTerminalId())));
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(documentLabel(kind), orderCode.trim()));
        if (customerName != null && !customerName.isBlank()) {
            sb.append(formatLine(i18n.both("paper.acompteClient"), customerName.trim()));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.articlesCommande"), WIDTH)).append("\n");
        if (lines != null) {
            for (com.intermarche.pos.ui.recall.RecalledDocument.Line line : lines) {
                if (line == null) {
                    continue;
                }
                BigDecimal quantity = line.quantity() == null ? BigDecimal.ONE : line.quantity();
                BigDecimal unitPrice = line.unitPrice() == null
                        ? BigDecimal.ZERO : line.unitPrice();
                BigDecimal lineTotal = unitPrice.multiply(quantity)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                String label = safe(line.label()) + (quantity.compareTo(BigDecimal.ONE) != 0
                        ? " x" + quantity.stripTrailingZeros().toPlainString() : "");
                sb.append(formatLine(label, money(DF.format(lineTotal)))).append("\n");
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(deposit))));
        java.util.List<com.intermarche.pos.domain.payment.DepositVat.Row> rows =
                com.intermarche.pos.domain.payment.DepositVat.rows(vatDetail);
        if (!rows.isEmpty()) {
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(center(i18n.both("paper.tvaAcompte"), WIDTH)).append("\n");
            for (java.util.Map<String, String> row : recap) {
                String code = row.get("code");
                sb.append(formatLine((code.isEmpty() ? "" : code + " ") + row.get("rate") + "%",
                        money(row.get("amount")))).append("\n");
                // BO-03-03-09: the base and the total beside the tax, so the
                // recap reads code, HT, TVA and TTC like the ticket's table.
                if (!row.get("base").isEmpty()) {
                    sb.append(formatLine("  " + i18n.both("paper.ht") + row.get("base"),
                            i18n.both("paper.ttc") + row.get("includingTax")));
                }
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(orderCode.trim(), WIDTH)).append("\n");
        sb.append(barcode(orderCode.trim())).append("\n");
        int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT, orderCode, sb.toString(), PrintPolicy.DOCUMENT_DEPOSIT, true);
        LOGGER.info("Exiting method printOrderDepositTicket");
        return printed;
    }

    /**
     * Prints the DEPOSIT INVOICE of a deposit taken on an order or a quote
     * (LC-08-04-15): an invoice, numbered in its own sequence, stating the store
     * that issues it, the articles and amounts of the order, the deposit and its
     * VAT per rate — due on collection — what was already paid and what remains,
     * while the sale itself is not done and the articles not sold yet.
     *
     * <p>Printed and archived under the deposit documents' policy, like the
     * deposit slip it goes with.
     *
     * @param kind the kind of the document the deposit was taken on; null reads
     *        as an order
     * @param number the deposit invoice number
     * @param orderCode the code of the order or quote
     * @param lines the order's lines, priced
     * @param total the order total, TTC, strictly positive
     * @param deposit the deposit taken, TTC, strictly positive
     * @param previouslyPaid what was paid on the order before this deposit
     * @return 1 when the invoice was printed, 0 when the policy suppressed the paper
     */
    public int printDepositInvoice(com.intermarche.pos.ui.recall.RecalledDocument.Kind kind,
                                   String number, String orderCode,
                                   java.util.List<com.intermarche.pos.ui.recall.RecalledDocument.Line> lines,
                                   BigDecimal total, BigDecimal deposit, BigDecimal previouslyPaid) {
        LOGGER.info("Entering method printDepositInvoice with number: " + number + ", deposit: " + deposit);
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        java.util.List<com.intermarche.pos.domain.store.Store> stores =
                com.intermarche.pos.domain.store.Store.listAll();
        if (!stores.isEmpty()) {
            com.intermarche.pos.domain.store.Store store = stores.get(0);
            sb.append(center(store.legalName == null || store.legalName.isBlank()
                    ? safe(store.name) : store.legalName, WIDTH)).append("\n");
            if (store.address != null) {
                sb.append(center(safe(store.address.streetLine1), WIDTH)).append("\n");
                sb.append(center(safe(store.address.postalCode) + " " + safe(store.address.city), WIDTH))
                        .append("\n");
            }
            if (store.siret != null && !store.siret.isBlank()) {
                sb.append(center(i18n.get("paper.siret") + " " + store.siret, WIDTH)).append("\n");
            }
            if (store.vatNumber != null && !store.vatNumber.isBlank()) {
                sb.append(center(i18n.get("paper.tvaIntracom") + " " + store.vatNumber, WIDTH)).append("\n");
            }
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.factureAcompte"), WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.numeroFacture"), number));
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(documentLabel(kind), orderCode));
        sb.append("-".repeat(WIDTH)).append("\n");
        VatBreakdown breakdown = new VatBreakdown(roundingMode());
        for (com.intermarche.pos.ui.recall.RecalledDocument.Line line : lines) {
            breakdown.add(line.vatRate(), line.total());
            sb.append(safe(line.label())).append("\n");
            sb.append(formatLine("  " + line.soldQuantity().stripTrailingZeros().toPlainString().replace('.', ',')
                    + " x " + DF.format(line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice()),
                    DF.format(line.total())));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.totalCommandeTtc"), money(DF.format(total))));
        sb.append(formatLine(i18n.both("paper.acompteTtc"), money(DF.format(deposit))));
        BigDecimal excluding = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (com.intermarche.pos.domain.payment.DepositVat.Share share
                : com.intermarche.pos.domain.payment.DepositVat.shares(breakdown.getBuckets(), deposit, total)) {
            excluding = excluding.add(share.excludingTax());
            vat = vat.add(share.vatAmount());
            sb.append(formatLine(i18n.both("paper.tva") + new com.intermarche.pos.domain.payment.DepositVat.Row(
                    share.rate(), share.vatAmount()).getRateFormatted(),
                    i18n.both("paper.ht") + DF.format(share.excludingTax())
                            + i18n.both("paper.tva") + DF.format(share.vatAmount())));
        }
        sb.append(formatLine(i18n.both("paper.acompteHt"), money(DF.format(excluding))));
        sb.append(formatLine(i18n.both("paper.acompteTva"), money(DF.format(vat))));
        if (previouslyPaid.signum() > 0) {
            sb.append(formatLine(i18n.both("paper.dejaVerse"), money(DF.format(previouslyPaid))));
        }
        sb.append(formatLine(i18n.both("paper.resteAPayer"),
                money(DF.format(total.subtract(previouslyPaid).subtract(deposit).max(BigDecimal.ZERO)))));
        sb.append("-".repeat(WIDTH)).append("\n");
        for (String mention : wrap(i18n.both("paper.mentionTvaAcompte"), WIDTH)) {
            sb.append(mention).append("\n");
        }
        int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.DEPOSIT,
                number, sb.toString(), PrintPolicy.DOCUMENT_DEPOSIT, true);
        LOGGER.info("Exiting method printDepositInvoice");
        return printed;
    }

    /**
     * The VAT RECAP of a deposit, rate by rate (BO-03-03-09): the code of the
     * regime, the rate, the base, the tax and the total, every figure already
     * written.
     *
     * <p>The tax is the one the deposit froze; the base is derived from it and
     * the rate, which is the only way back from the compact detail, and is left
     * empty for a zero rate, where no tax says nothing about a base. The code is
     * resolved only where the shop prints VAT codes, which is also when the
     * ticket's own table states them (BO-03-03-37).
     *
     * @param vatDetail the compact detail, or null
     * @return one map per rate, keys {@code rate}, {@code code}, {@code base},
     *         {@code amount} and {@code includingTax}
     */
    java.util.List<java.util.Map<String, String>> depositVatRecap(String vatDetail) {
        java.util.List<java.util.Map<String, String>> recap = new java.util.ArrayList<>();
        boolean codes = posSettingsService.ticketVatCode();
        for (com.intermarche.pos.domain.payment.DepositVat.Row row
                : com.intermarche.pos.domain.payment.DepositVat.rows(vatDetail)) {
            String rateText = row.getRateFormatted();
            String code = codes ? safe(vatLetterForRate(row.rate())) : "";
            java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("rate", rateText.substring(0, rateText.length() - 1));
            entry.put("code", code);
            BigDecimal vat = row.vatAmount().setScale(2, java.math.RoundingMode.HALF_UP);
            if (row.rate().signum() == 0) {
                entry.put("base", "");
                entry.put("includingTax", "");
            } else {
                BigDecimal base = vat.divide(row.rate(), 2, java.math.RoundingMode.HALF_UP);
                entry.put("base", DF.format(base));
                entry.put("includingTax", DF.format(base.add(vat)));
            }
            entry.put("amount", DF.format(vat));
            recap.add(entry);
        }
        return recap;
    }

    /**
     * The printed name of the document a deposit was taken on: a quote is not
     * an order (LC-06-02-04).
     *
     * @param kind the kind of the document, null for an order
     * @return the label, in the receipt languages
     */
    private String documentLabel(com.intermarche.pos.ui.recall.RecalledDocument.Kind kind) {
        if (kind == null) {
            return i18n.both("paper.acompteCommande");
        }
        return switch (kind) {
            case DEVIS -> i18n.both("paper.acompteDevis");
            case BON_VENTE -> i18n.both("paper.acompteBonVente");
            case CLICK_COLLECT -> i18n.both("paper.acompteClickCollect");
            case SELF_SCAN -> i18n.both("paper.acompteSelfScan");
            case QBUSTING -> i18n.both("paper.acompteQbusting");
            case ECOMMERCE -> i18n.both("paper.acompteEcommerce");
            case MOBILITY -> i18n.both("paper.acompteMobilite");
            case COMMANDE -> i18n.both("paper.acompteCommande");
        };
    }

    /**
     * Prints the slip of a settlement paid by GIFT CARD (BO-03-03-11).
     *
     * <p>Distinct from {@code printGiftCardVoucher}, which is the paper a
     * customer BUYING a card walks away with. This one is for a customer
     * SPENDING one, and what it adds is the balance the card still carries —
     * the one question a customer asks at the counter.
     *
     * <p>The card number is printed MASKED, last four characters only: a gift
     * card is bearer money, and a full number on a receipt left in a trolley is
     * a card somebody else can spend.
     *
     * @param ticketNumber the ticket the settlement is on, or null
     * @param cardNumber the gift-card number, masked before printing, or null
     * @param amount the amount settled, or null
     * @param remainingBalance the balance the card still carries, or null
     * @return 1 when a slip was printed, 0 when there was nothing to print
     */
    public int printGiftCardPaymentReceipt(String ticketNumber, String cardNumber,
                                           BigDecimal amount, BigDecimal remainingBalance) {
        return printGiftCardPaymentReceipt(ticketNumber, cardNumber, amount, null,
                remainingBalance, null, null, null);
    }

    /**
     * Prints the slip of a settlement paid by gift card, with what the payment
     * hub answered (BO-03-03-11, LC-07-11-03): the balance before and after, the
     * provider, its code and the hub's transaction.
     *
     * @param ticketNumber the ticket the settlement is on, or null
     * @param cardNumber the gift-card number, masked before printing, or null
     * @param amount the amount settled, or null
     * @param balanceBefore the balance the card carried before, or null
     * @param remainingBalance the balance the card still carries, or null
     * @param provider the provider of the card, or null
     * @param approval the provider's code for the payment, or null
     * @param transaction the hub's transaction identifier, or null
     * @return 1 when a slip was printed, 0 when there was nothing to print
     */
    public int printGiftCardPaymentReceipt(String ticketNumber, String cardNumber,
                                           BigDecimal amount, BigDecimal balanceBefore,
                                           BigDecimal remainingBalance, String provider,
                                           String approval, String transaction) {
        return printGiftCardPaymentReceipt(ticketNumber, cardNumber, amount, balanceBefore,
                remainingBalance, provider, approval, transaction, null);
    }

    /**
     * Prints the slip of a settlement paid by gift card, carrying the sentence
     * the shop wrote for what the provider answered (BO-03-08-07).
     *
     * <p>The provider's own message never reaches the paper: only the shop's
     * TICKET TEXT for that message does, with its {@code {montant}},
     * {@code {solde}} and {@code {carte}} tokens replaced by the figures of
     * this settlement. The slip is an associated document of the ticket, and
     * prints under conditional printing only when the shop forces it
     * (BO-03-03-01).
     *
     * @param ticketNumber the ticket the settlement is on, or null
     * @param cardNumber the gift-card number, masked before printing, or null
     * @param amount the amount settled, or null
     * @param balanceBefore the balance the card carried before, or null
     * @param remainingBalance the balance the card still carries, or null
     * @param provider the provider of the card, or null
     * @param approval the provider's code for the payment, or null
     * @param transaction the hub's transaction identifier, or null
     * @param providerText the shop's ticket text for the provider's message,
     *        or null when the shop wrote none
     * @return 1 when a slip was printed, 0 when there was nothing to print or
     *         the policy kept it from the printer
     */
    public int printGiftCardPaymentReceipt(String ticketNumber, String cardNumber,
                                           BigDecimal amount, BigDecimal balanceBefore,
                                           BigDecimal remainingBalance, String provider,
                                           String approval, String transaction,
                                           String providerText) {
        LOGGER.info("Entering method printGiftCardPaymentReceipt with ticketNumber: "
                + ticketNumber + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method printGiftCardPaymentReceipt");
            return 0;
        }
        String providerLines = providerTextLines(providerText, amount, remainingBalance, cardNumber);
        java.util.Map<String, Object> data = giftCardPaymentDocumentData(ticketNumber, cardNumber,
                amount, balanceBefore, remainingBalance, provider, approval, transaction);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> document = new java.util.HashMap<>(
                (java.util.Map<String, Object>) data.get("document"));
        document.put("providerMessage", providerLines.strip());
        data.put("document", document);
        String administered = administeredLayout(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.GIFT_CARD_PAYMENT, data);
        if (administered != null) {
            int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER,
                    ticketNumber, administered, PrintPolicy.DOCUMENT_GIFT_CARD_PAYMENT, true);
            LOGGER.info("Exiting method printGiftCardPaymentReceipt");
            return printed;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(documentTitle(
                com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.GIFT_CARD_PAYMENT,
                i18n.both("paper.paiementCarteCadeau")), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (ticketNumberService != null) {
            sb.append(String.format(i18n.get("paper.caisse2"),
                    safe(ticketNumberService.getTerminalId())));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(i18n.both("paper.ticketNumero"), safe(ticketNumber)));
        sb.append(formatLine(i18n.both("paper.carte"), maskedCardNumber(cardNumber)));
        sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(amount))));
        if (balanceBefore != null) {
            sb.append(formatLine(i18n.both("paper.ancienSolde"), money(DF.format(balanceBefore))));
        }
        if (remainingBalance != null) {
            sb.append(formatLine(i18n.both("paper.soldeRestant"),
                    money(DF.format(remainingBalance))));
        }
        sb.append(hubLines(provider, approval, transaction));
        sb.append(providerLines);
        sb.append("-".repeat(WIDTH)).append("\n");
        int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER,
                ticketNumber, sb.toString(), PrintPolicy.DOCUMENT_GIFT_CARD_PAYMENT, true);
        LOGGER.info("Exiting method printGiftCardPaymentReceipt");
        return printed;
    }

    /**
     * The shop's sentence for a provider's message, as the paper prints it
     * (BO-03-08-07): its tokens replaced by the figures of the operation and
     * wrapped to the paper width, never truncated.
     *
     * @param providerText the administered ticket text, or null
     * @param amount the amount of the operation, or null
     * @param balance the balance the card or article carries after it, or null
     * @param cardNumber the card or article number, masked before printing, or null
     * @return the lines and their newlines, empty when the shop wrote nothing
     */
    String providerTextLines(String providerText, BigDecimal amount, BigDecimal balance,
                             String cardNumber) {
        if (providerText == null || providerText.isBlank()) {
            return "";
        }
        String text = providerText.trim()
                .replace("{montant}", amount == null ? "" : money(DF.format(amount)))
                .replace("{solde}", balance == null ? "" : money(DF.format(balance)))
                .replace("{carte}", maskedCardNumber(cardNumber));
        StringBuilder lines = new StringBuilder();
        for (String line : wrap(text, WIDTH)) {
            lines.append(line).append("\n");
        }
        return lines.toString();
    }

    /**
     * Builds the gift-card payment slip as an administered layout sees it
     * (BO-03-03-11).
     *
     * @param ticketNumber the ticket the settlement is on, or null
     * @param cardNumber the gift-card number, or null
     * @param amount the amount settled
     * @param balanceBefore the balance the card carried before, or null
     * @param remainingBalance the balance the card still carries, or null
     * @param provider the provider of the card, or null
     * @param approval the provider's code for the payment, or null
     * @param transaction the hub's transaction identifier, or null
     * @return the document values
     */
    java.util.Map<String, Object> giftCardPaymentDocumentData(String ticketNumber,
                                                              String cardNumber,
                                                              BigDecimal amount,
                                                              BigDecimal balanceBefore,
                                                              BigDecimal remainingBalance,
                                                              String provider,
                                                              String approval,
                                                              String transaction) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        data.put("terminal", ticketNumberService == null
                ? "" : safe(ticketNumberService.getTerminalId()));
        data.put("operator", "");
        data.put("date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(ticketNumber),
                "cardNumber", maskedCardNumber(cardNumber),
                "amount", DF.format(amount),
                "balanceBefore", balanceBefore == null ? "" : DF.format(balanceBefore),
                "remainingBalance", remainingBalance == null
                        ? "" : DF.format(remainingBalance),
                "provider", safe(provider),
                "approval", safe(approval),
                "transaction", safe(transaction)));
        return data;
    }

    /**
     * The lines a payment hub's answer adds to a slip (LC-02-09-03,
     * LC-07-11-03): the provider, the provider's own code and the hub's
     * transaction number, each printed only when the hub stated it.
     *
     * @param provider the provider, or null
     * @param approval the provider's code, or null
     * @param transaction the hub's transaction identifier, or null
     * @return the lines, possibly empty
     */
    private String hubLines(String provider, String approval, String transaction) {
        StringBuilder sb = new StringBuilder();
        if (provider != null) {
            sb.append(formatLine(i18n.both("paper.fournisseur"), provider));
        }
        if (approval != null) {
            sb.append(formatLine(i18n.both("paper.codeFournisseur"), approval));
        }
        if (transaction != null) {
            sb.append(formatLine(i18n.both("paper.transactionHub"), transaction));
        }
        return sb.toString();
    }

    /**
     * Whether a ticket mixes sales and returns (LC-05-07-02).
     *
     * @param ticket the ticket
     * @return true when it carries at least one sold and one returned line
     */
    static boolean mixed(Ticket ticket) {
        boolean sold = false;
        boolean returned = false;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            if (line.returnLine) {
                returned = true;
            } else {
                sold = true;
            }
        }
        return sold && returned;
    }

    /**
     * Prints the DETAIL OF THE RETURNS of a ticket mixing sales and returns,
     * after its receipt, where the store asks for it (LC-05-07-02): the articles
     * handed back, their quantities, prices and totals, the value of the returns
     * the receipt carries, and what the returns change to the advantages.
     *
     * @param ticketId the closed ticket
     * @return 1 when the detail was printed, 0 otherwise
     */
    public int printReturnDetail(Long ticketId) {
        LOGGER.info("Entering method printReturnDetail with ticketId: " + ticketId);
        Ticket ticket = ticketId == null ? null : Ticket.findById(ticketId);
        if (ticket == null || !posSettingsService.returnSeparateDetail() || !mixed(ticket)) {
            LOGGER.info("Exiting method printReturnDetail");
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(i18n.both("paper.detailRetours"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket"), ticket.ticketNumber));
        sb.append(String.format(i18n.get("paper.date"), ticket.creationDate
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        BigDecimal total = BigDecimal.ZERO;
        for (TicketLine line : ticket.lines) {
            if (line.cancelled || !line.returnLine) {
                continue;
            }
            sb.append(line.productLabel.length() > WIDTH ? line.productLabel.substring(0, WIDTH)
                    : line.productLabel).append("\n");
            sb.append("  ").append(line.quantity.stripTrailingZeros().toPlainString().replace('.', ','))
                    .append(" x ").append(money(DF.format(line.unitPrice))).append("\n");
            sb.append(String.format("%" + WIDTH + "s%n", DF.format(line.totalPrice)));
            total = total.add(line.totalPrice);
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.totalRetours"), money(DF.format(total))));
        sb.append(center(i18n.both("paper.recalculAvantages"), WIDTH)).append("\n");
        sb.append(i18n.get("paper.retoursExclusAvantages"));
        if (ticket.fidelityCard != null) {
            sb.append(i18n.get("paper.cagnotteCoupons"));
            sb.append(i18n.get("paper.recalculTransmisProgramme"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER,
                ticket.ticketNumber, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printReturnDetail");
        return 1;
    }

    /**
     * Prints the ACTIVATION TICKET of a dematerialised article, after the
     * receipt of the sale that activated it (LC-02-09-03): the article, its
     * activation code, its amount, the balance before and after the activation,
     * the hub's transaction number and the provider's code.
     *
     * <p>The activation code is printed whole: it is what the customer was sold.
     *
     * @param ticketNumber the sale's number, or null
     * @param label the article label, or null
     * @param activationCode the article's unique code, or null
     * @param amount the amount activated, or null
     * @param balanceBefore what the article carried before, or null
     * @param balanceAfter what it carries after, or null
     * @param transaction the hub's transaction identifier, or null
     * @param provider the provider, or null
     * @param approval the provider's code, or null
     * @return 1 when a ticket was printed, 0 when there was nothing to print
     */
    public int printActivationTicket(String ticketNumber, String label, String activationCode,
                                     BigDecimal amount, BigDecimal balanceBefore,
                                     BigDecimal balanceAfter, String transaction,
                                     String provider, String approval) {
        return printActivationTicket(ticketNumber, label, activationCode, amount, balanceBefore,
                balanceAfter, transaction, provider, approval, null);
    }

    /**
     * Prints the activation ticket of a dematerialised article, carrying the
     * sentence the shop wrote for what the provider answered (BO-03-08-07).
     *
     * <p>An associated document of the ticket: under conditional printing it
     * comes out only when the shop forces it (BO-03-03-01), and its customer
     * copy is kept either way.
     *
     * @param ticketNumber the sale's number, or null
     * @param label the article label, or null
     * @param activationCode the article's unique code, or null
     * @param amount the amount activated, or null
     * @param balanceBefore what the article carried before, or null
     * @param balanceAfter what it carries after, or null
     * @param transaction the hub's transaction identifier, or null
     * @param provider the provider, or null
     * @param approval the provider's code, or null
     * @param providerText the shop's ticket text for the provider's message,
     *        or null when the shop wrote none
     * @return 1 when the ticket was printed, 0 when there was nothing to print
     *         or the policy kept it from the printer
     */
    public int printActivationTicket(String ticketNumber, String label, String activationCode,
                                     BigDecimal amount, BigDecimal balanceBefore,
                                     BigDecimal balanceAfter, String transaction,
                                     String provider, String approval, String providerText) {
        LOGGER.info("Entering method printActivationTicket with ticketNumber: " + ticketNumber);
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method printActivationTicket");
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(logoHeader());
        sb.append(center(i18n.both("paper.ticketActivation"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (ticketNumberService != null) {
            sb.append(String.format(i18n.get("paper.caisse2"),
                    safe(ticketNumberService.getTerminalId())));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append(formatLine(i18n.both("paper.ticketNumero"), safe(ticketNumber)));
        sb.append(safe(label)).append("\n");
        sb.append(formatLine(i18n.both("paper.codeActivation"), safe(activationCode)));
        sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(amount))));
        if (balanceBefore != null) {
            sb.append(formatLine(i18n.both("paper.ancienSolde"), money(DF.format(balanceBefore))));
        }
        if (balanceAfter != null) {
            sb.append(formatLine(i18n.both("paper.nouveauSolde"), money(DF.format(balanceAfter))));
        }
        sb.append(hubLines(provider, approval, transaction));
        sb.append(providerTextLines(providerText, amount, balanceAfter, activationCode));
        sb.append("-".repeat(WIDTH)).append("\n");
        int printed = printAssociatedCustomerCopy(com.intermarche.pos.domain.sale.CustomerDocument.Kind.VOUCHER,
                ticketNumber, sb.toString(), PrintPolicy.DOCUMENT_DEMATERIALISED, true);
        LOGGER.info("Exiting method printActivationTicket");
        return printed;
    }

    /**
     * Masks a gift-card number down to its last four characters
     * (BO-03-03-11).
     *
     * <p>A gift card is BEARER money: a full number on a receipt left in a
     * trolley is a card somebody else can spend. A number too short to mask is
     * printed whole — there is nothing to hide in four characters — and an
     * absent one prints nothing.
     *
     * @param cardNumber the gift-card number, or null
     * @return the masked number, never null
     */
    static String maskedCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "";
        }
        String trimmed = cardNumber.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * Builds the cancellation slip as an administered layout sees it
     * (BO-03-02-35).
     *
     * @param ticketNumber the ticket the settlement was on, or null
     * @param tenderLabel the wording of the cancelled tender
     * @param amount the amount that was given back
     * @return the document values
     */
    java.util.Map<String, Object> paymentCancellationDocumentData(String ticketNumber,
                                                                  String tenderLabel,
                                                                  BigDecimal amount) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        data.put("terminal", ticketNumberService == null
                ? "" : safe(ticketNumberService.getTerminalId()));
        data.put("operator", "");
        data.put("date", now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        data.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        data.put("document", java.util.Map.of(
                "number", safe(ticketNumber),
                "tenderLabel", tenderLabel.trim(),
                "amount", DF.format(amount)));
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
                movementDocumentData(i18n.get("paper.prelevement2"), methodLabel, null, null, amount, lines,
                        operator, terminalId));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printWithdrawalTicket");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.prelevement2"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.moyen"), methodLabel));
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
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
        sb.append(formatLine(i18n.get("paper.totalPreleve"), money(DF.format(amount))));
        sb.append("\n");
        sb.append(i18n.get("paper.signature"));
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printWithdrawalTicket");
    }

    /**
     * Prints the CONTROL COUNT of the float (EXP-04): the slip a cashier keeps after
     * counting her drawer at the start of the day, denomination by denomination.
     *
     * <p>IT RECORDS NOTHING, and that is the point: the calculator is a scratchpad,
     * no movement is written and no event emitted. The paper is the only trace, and
     * it is what the cashier shows her manager when the float is right — or wrong.
     * The slip says COMPTAGE DE CONTROLE rather than anything resembling a
     * declaration, because nothing here is opposable.
     *
     * @param total the counted total
     * @param lines the per-denomination detail, already formatted label/value
     * @param operator the operator's name, blank when unknown
     * @param terminalId the register the count was made on
     */
    public void printFloatCountTicket(BigDecimal total, java.util.List<String[]> lines,
            String operator, String terminalId) {
        LOGGER.info("Entering method printFloatCountTicket with total: " + total + ", lines: " + lines + ", operator: " + operator + ", terminalId: " + terminalId);
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.comptageControle"), WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.fondCaisse2"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
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
        sb.append(formatLine(i18n.get("paper.totalCompte"), money(DF.format(total))));
        sb.append("\n");
        sb.append(center(i18n.get("paper.documentControleAucunMouvement"), WIDTH)).append("\n");
        sb.append("\n");
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printFloatCountTicket");
    }

    /**
     * Prints the JUSTIFICATIF of a flat cash movement — an expense, a customer
     * down-payment or a cash-count declaration: the paper that justifies the cash
     * that left or entered the drawer outside a sale.
     *
     * <p>IT EXISTS BECAUSE THESE THREE PRINTED NOTHING. The withdrawal and the
     * coin supply have had their bordereau from the start; an expense taken from the
     * drawer left no trace the shop could put in the till with the receipt, which is
     * exactly what a cash audit asks for. Built-in layout only, like the other two
     * drawer papers, and the same signature line: the movement is countersigned, not
     * merely recorded.
     *
     * @param typeLabel the movement's wording as the cashier chose it
     * @param amount the movement's amount
     * @param reason the reason or the reference carried by the movement, blank when none
     * @param tenderLabel the tender the movement concerns, blank for cash
     * @param operator the operator's name, blank when unknown
     * @param terminalId the register the movement was recorded on
     */
    public void printMovementTicket(String typeLabel, BigDecimal amount, String reason,
            String tenderLabel, String operator, String terminalId) {
        LOGGER.info("Entering method printMovementTicket with typeLabel: " + typeLabel + ", amount: " + amount + ", reason: " + reason + ", tenderLabel: " + tenderLabel + ", operator: " + operator + ", terminalId: " + terminalId);
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.mouvementCaisse"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.type"), typeLabel == null ? "" : typeLabel));
        if (reason != null && !reason.isBlank()) {
            sb.append(formatLine(i18n.get("paper.motif2"), reason));
        }
        if (tenderLabel != null && !tenderLabel.isBlank()) {
            sb.append(formatLine(i18n.get("paper.moyen2"), tenderLabel));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.montant"), money(DF.format(amount))));
        sb.append("\n");
        sb.append(i18n.get("paper.signature"));
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printMovementTicket");
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
     * @param operatorNumber the recorded operator badge number, null when unknown
     * @param terminalId the register the apport was made on
     */
    public void printDepositTicket(BigDecimal amount, java.util.List<String[]> lines,
            String operator, String operatorNumber, String terminalId) {
        if (!posSettingsService.cashDepositPrint()) return;
        LOGGER.info("Entering method printDepositTicket with amount: " + amount + ", lines: " + lines + ", operator: " + operator + ", terminalId: " + terminalId);
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.apportMonnaie"), WIDTH)).append("\n");
        if (operatorNumber != null && !operatorNumber.isBlank()) {
            sb.append(String.format(i18n.get("paper.numeroOperateur"), operatorNumber));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
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
        sb.append(formatLine(i18n.get("paper.totalApporte"), money(DF.format(amount))));
        sb.append("\n");
        sb.append(i18n.get("paper.signature"));
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
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.bordereauReglement"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report != null && report.session != null) {
            sb.append(String.format(i18n.get("paper.session"), report.session.sessionNumber));
            sb.append(String.format(i18n.get("paper.caisse"), report.session.terminalId));
        }
        sb.append(String.format(i18n.get("paper.date2"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        if (report != null) {
            for (CashSessionService.SessionReport.MethodRow row : report.getMethodRows()) {
                sb.append(formatLine(methodLabel(row.method()), money(row.amountFormatted())));
            }
            sb.append("-".repeat(WIDTH)).append("\n");
            sb.append(formatLine(i18n.get("paper.totalTtc"), money((report.getTotalIncludingTaxFormatted()))));
        }
        sb.append("\n");
        sb.append(i18n.get("paper.signature"));
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
                movementDocumentData(i18n.get("paper.transfertReglement"), null, fromLabel, toLabel, amount,
                        null, operator, terminalId));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printTransferTicket");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.transfertReglement"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.get("paper.de"), fromLabel));
        sb.append(formatLine(i18n.get("paper.vers"), toLabel));
        sb.append(formatLine(i18n.get("paper.montant"), money(DF.format(amount))));
        sb.append("\n");
        sb.append(i18n.get("paper.signature"));
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
     * @param total what the order is worth — the sum the cashier hands over to be
     *        given the rolls — or null on an empty slip, which states no amount
     */
    public void printChangeOrder(String terminalId, String operatorNumber, String operatorName,
            java.util.List<String[]> rows, BigDecimal total) {
        LOGGER.info("Entering method printChangeOrder with terminalId: " + terminalId + ", operatorNumber: " + operatorNumber + ", total: " + total);
        StringBuilder sb = new StringBuilder();
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.commandeMonnaie"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        if (terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), terminalId));
        }
        String operator = ((operatorNumber == null ? "" : operatorNumber) + " "
                + (operatorName == null ? "" : operatorName)).trim();
        if (!operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
        }
        sb.append(String.format(i18n.get("paper.date"), java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format("%-20s%10s%12s%n", i18n.get("paper.denomination"), i18n.get("paper.montant"), i18n.get("paper.nbrRoul")));
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
        // What the order is WORTH, and a line to countersign: the cashier gives this
        // paper and that sum to her manager, who hands back the rolls. The slip used
        // to list the denominations and leave the amount to be added up by hand.
        if (total != null) {
            sb.append(formatLine(i18n.get("paper.totalRemettre"), money(DF.format(total))));
            sb.append("\n");
            sb.append(i18n.get("paper.signature"));
        }
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
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.ticketAbandonne"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket2"), ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), ticket.terminalId));
        }
        sb.append(String.format(i18n.get("paper.date"),
                java.time.LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (operator != null && !operator.isBlank()) {
            sb.append(String.format(i18n.get("paper.operateur2"), operator));
        }
        // LC-04-04-11: the reason, printed on the paper and not only journalled.
        if (reason != null && !reason.isBlank()) {
            sb.append(String.format(i18n.get("paper.motif"), reason));
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
                        money(DF.format(line.totalPrice))));
            }
        }
        if (withDetail) {
            sb.append("-".repeat(WIDTH)).append("\n");
        }
        sb.append(formatLine(i18n.get("paper.montantAbandonne"), money(DF.format(abandoned))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.get("paper.aucunEncaissement"), WIDTH)).append("\n");
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
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.articlesEnlever"), WIDTH)).append("\n");
        // WHERE TO GO. The customer pays at the till and DRIVES to the collection
        // point — the BATI counter in a hardware store, the DRIVE in a food one —
        // so the slip that proves what is owed to them has to name the place. One
        // place per store; blank when none is administered, and the slip then
        // names none rather than inventing one.
        String collectPoint = posSettingsService.collectPointLabel();
        if (collectPoint != null && !collectPoint.isBlank()) {
            sb.append(center(i18n.format("paper.retraitAu", collectPoint.trim()), WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket2"), ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format(i18n.get("paper.date"),
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.article"), i18n.both("paper.retirer"))).append("\n");
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
            sb.append(i18n.get("paper.retireReste"));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(i18n.get("paper.dateRetrait"));
        sb.append(i18n.get("paper.vendeur2"));
        sb.append(i18n.get("paper.signature2"));
        sb.append(i18n.get("paper.client"));
        sb.append(i18n.get("paper.signature"));
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        // TWO COPIES, AND THEY ARE NOT THE SAME SHEET. The slip is not a fiscal
        // document — it is the proof that goods left the shop. The collection
        // desk KEEPS one, which the manager collects in the evening; the customer
        // KEEPS one, carrying the per-article "retire / reste" boxes they come
        // back with. One copy cannot do both, and two identical ones would leave
        // nobody able to tell an honoured slip from an open one. The count is
        // administered, because the process belongs to the store.
        int copies = posSettingsService.collectVoucherCopies();
        for (int copy = 1; copy <= copies; copy++) {
            String mark = copies == 1 ? ""
                    : center(i18n.both(copy == 1 ? "paper.exemplaireMagasin"
                            : "paper.exemplaireClient"), WIDTH) + "\n"
                            + "-".repeat(WIDTH) + "\n";
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.COLLECTION, ticketId, mark + sb);
            hardwareService.printReceipt(mark + sb);
            hardwareService.cutPaper();
        }
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
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (ticket.store != null) {
            sb.append(center(ticket.store.name, WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.bonEchange"), WIDTH)).append("\n");
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(String.format(i18n.get("paper.ticket2"), ticket.ticketNumber));
        if (ticket.terminalId != null) {
            sb.append(String.format(i18n.get("paper.caisse2"), ticket.terminalId));
        }
        if (ticket.creationDate != null) {
            sb.append(String.format(i18n.get("paper.date"),
                    ticket.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        boolean whole = lineIds == null || lineIds.isEmpty();
        com.intermarche.pos.domain.catalog.UnitFormat units =
                com.intermarche.pos.domain.catalog.UnitFormat.orDefault(posSettingsService.unitFormat());
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) continue;
            if (!whole && !lineIds.contains(line.id)) continue;
            String label = line.productLabel.length() > 30
                    ? line.productLabel.substring(0, 30) : line.productLabel;
            // The quantity is written as the receipt writes quantities, ahead of
            // the label: on a voucher stating that no amount figures on it, a
            // figure in the money format, in the column where a price stands,
            // reads as a price.
            sb.append(String.format("%-8s %s", exchangeQuantity(line, units), label)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(center(i18n.both("paper.aucunMontantNeFigure"), WIDTH)).append("\n");
        sb.append("\n").append(barcode(ticket.ticketNumber)).append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.EXCHANGE, ticketId, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printExchangeVoucher");
    }

    /**
     * The quantity of a line on the exchange voucher, written as the receipt
     * writes quantities (BO-10-08-28, BO-10-08-29): a weighed line's weight with
     * the administered decimals and symbol, {@code 1,500 kg}; a count without
     * decimals, {@code 1}; a unit-of-measure quantity with its decimals and its
     * unit, {@code 2,5 m}.
     *
     * @param line the sold line
     * @param units the administered writing of quantities
     * @return the written quantity
     */
    private static String exchangeQuantity(TicketLine line,
            com.intermarche.pos.domain.catalog.UnitFormat units) {
        if (line.plu != null && !line.plu.isEmpty()) {
            return units.weight(line.quantity);
        }
        String measured = units.measure(line.quantity);
        return line.unitName == null || line.unitName.isBlank() ? measured : measured + " " + line.unitName;
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
                cardDocumentData(i18n.both("paper.credit"), refund.totalAmount, null, refund.terminalId,
                        refund.creationDate == null ? null : refund.creationDate.format(
                                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        if (administered != null) {
            archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, refund.id, administered);
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printCardCreditReceipt");
            return;
        }
        StringBuilder sb = new StringBuilder();
        cardHeader(sb, null, i18n.both("paper.credit"));
        sb.append(String.format(i18n.get("paper.caisse2"), refund.terminalId));
        sb.append(String.format(i18n.get("paper.date"),
                refund.creationDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        sb.append("-".repeat(WIDTH)).append("\n");
        sb.append(formatLine(i18n.both("paper.montantCredite"), money(DF.format(refund.totalAmount))));
        sb.append("\n");
        archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, refund.id, sb.toString());
        hardwareService.printReceipt(sb.toString());
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printCardCreditReceipt");
    }

    /**
     * Prints the card receipt of a NOT-COMPLETED transaction (LC-08-03-12):
     * the terminal's own TNA frame when it supplied one — printed verbatim,
     * the monetique owns that text — and otherwise a register-built slip.
     * LC-07-04-10 forbids adding a header or newline to a supplied vendor frame.
     *
     * @param amount the amount the refused transaction was requested for
     * @param frame the terminal's TNA frame, or null when it supplied none
     */
    public void printCardTnaReceipt(BigDecimal amount, String frame) {
        LOGGER.info("Entering method printCardTnaReceipt");
        if (frame != null && !frame.isBlank()) {
            printTerminalFrame(frame);
            return;
        }
        // BO-03-03: the store's own layout first, the built-in one otherwise.
        String administered = administeredLayout(com.intermarche.pos.domain.setting.DocumentTemplate.DocumentType.CARD_RECEIPT,
                cardDocumentData(i18n.both("paper.abandonDebit"), amount, frame, null, null));
        if (administered != null) {
            hardwareService.printReceipt(administered);
            hardwareService.cutPaper();
            LOGGER.info("Exiting method printCardTnaReceipt");
            return;
        }
        StringBuilder sb = new StringBuilder();
        cardHeader(sb, null, i18n.both("paper.abandonDebit"));
        if (amount != null) {
            sb.append(formatLine(i18n.both("paper.montant"), money(DF.format(amount))));
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
        sb.append(center(i18n.get("paper.intermarche"), WIDTH)).append("\n");
        if (storeName != null) {
            sb.append(center(storeName, WIDTH)).append("\n");
        }
        sb.append(center(i18n.both("paper.ticketCarteBancaire"), WIDTH)).append("\n");
        if (mention != null) {
            sb.append(center("*** " + mention + " ***", WIDTH)).append("\n");
        }
        sb.append("-".repeat(WIDTH)).append("\n");
    }

    /** LC-12-07-01: prints exactly the approved operator revenue snapshot, without drawer-wide balances.
     * @param report operator-only completed sales and settlements
     * @param operator connected operator's display name
     */
    public void printOperatorReport(CashSessionService.SessionReport report, String operator) {
        StringBuilder text = new StringBuilder();
        text.append(center(i18n.get("operatorReport.title"), WIDTH)).append('\n');
        text.append(operator).append('\n');
        text.append(String.format(i18n.get("paper.session"), report.session.sessionNumber));
        text.append(String.format(i18n.get("paper.caisse"), report.session.terminalId));
        text.append(String.format(i18n.get("paper.ouverte"), report.session.openingDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))));
        appendOperationRows(text, report);
        hardwareService.printReceipt(text.toString());
        hardwareService.cutPaper();
    }

    /** LC-12-07-02: prints only the configured rubric rows from the approved snapshot.
     * @param text receipt buffer
     * @param report approved report, identical to the screen data
     */
    private void appendOperationRows(StringBuilder text, CashSessionService.SessionReport report) {
        if (report.operationRows.isEmpty()) return;
        text.append('\n').append(center(i18n.get("report.operations"), WIDTH)).append('\n');
        for (var row : report.operationRows) {
            text.append(formatLine(i18n.get(row.key()) + " " + methodLabel(row.tender()),
                    row.count() + " / " + money(row.amountFormatted())));
        }
    }

    /**
     * The heading of one ventilation row of the sale ticket and of its VAT
     * coupon: the regime's code where the shop prints codes, read by the
     * bucket's regime number so two regimes of one percentage read apart
     * (BO-02-02-01), then the rate — {@code EXO} for an exemption
     * (BO-02-03-26) — and the third-party mention for the VAT of a sale made
     * on behalf of a third party (BO-02-03-42). An exemption bucket naming no
     * regime prints no code: the first regime of the 0&nbsp;% rate is not
     * the exemption's.
     *
     * @param bucket the ventilation bucket
     * @return the heading printed in front of the amounts
     */
    private String ventilationLabel(VatBreakdown.Bucket bucket) {
        String code = posSettingsService.ticketVatCode() && (bucket.number != null || !bucket.exempt)
                ? vatLetter(bucket.rate, bucket.number) : null;
        return (code == null ? "" : code + " ") + bucket.getRateFormatted()
                + (bucket.thirdParty ? " " + i18n.both("paper.tvaCompteTiers") : "");
    }

    /** LC-02-10-03: maps the non-negative regime identity to a stable alphabetic code (0=A, 26=AA).
     * @param rate the historical article VAT fraction
     * @return the regime letter, or null when no trustworthy regime identity exists
     */
    private String vatLetterForRate(BigDecimal rate) {
        return vatLetter(rate, null);
    }

    /**
     * LC-02-10-03, BO-02-02-01: the letter of the regime a line or a bucket was
     * taxed under, read by its number when it names one, by its rate otherwise.
     *
     * @param rate the historical VAT fraction
     * @param number the regime number, or null
     * @return the regime letter, or null when no trustworthy regime identity exists
     */
    private String vatLetter(BigDecimal rate, Integer number) {
        if (rate == null) return null;
        var regime = regimeOf(rate, number);
        return regime == null ? null : regime.code();
    }

    /**
     * BO-02-02-01: the regime a rate and a regime number name — by the number
     * when one is kept, by the rate alone, as before the number existed,
     * otherwise.
     *
     * @param rate the VAT fraction
     * @param number the regime number, or null
     * @return the regime, or null when none answers
     */
    private com.intermarche.pos.domain.catalog.VatRate regimeOf(BigDecimal rate, Integer number) {
        return number == null ? com.intermarche.pos.domain.catalog.VatRate.findByRate(rate)
                : com.intermarche.pos.domain.catalog.VatRate.resolve(rate, number);
    }
}
