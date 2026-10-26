package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.RestrictedTender;
import com.intermarche.pos.domain.payment.SocialCardPayment;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.sale.FiscalDocument;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Issues the PORTUGUESE FISCAL DOCUMENTS of this register (LC-08-01-05, LC-08-01-06,
 * LC-08-04-18 to -23, LC-05-07-03).
 *
 * <p>A sale is stated on a FATURA SIMPLIFICADA carrying the customer's NIF, or on a
 * FATURA when the customer asked for one before the sale was printed — never both.
 * A sale paid in part with the social purchase card is stated on TWO documents of
 * the same transaction, the eligible lines the card paid on the first. A return is
 * stated on a NOTA DE CRÉDITO; a simplified invoice turned into an invoice after the
 * sale is cancelled by a credit note and replaced by the invoice; an invoice may be
 * followed by its RECIBO.
 *
 * <p>Every document is numbered in its series — the register and the year, through
 * the fiscal sequence the register already keeps per type — carries its ATCUD, the
 * signature chaining it to the previous document of its series, and the payload of
 * the fiscal QR code (Portaria 195/2020).
 */
@ApplicationScoped
public class PortugueseFiscalService {

    private static final Logger LOGGER = Logger.getLogger(PortugueseFiscalService.class);

    /** The tax number a document states for a customer who gave none. */
    public static final String FINAL_CONSUMER = "999999990";

    /** The social purchase card and the article attribute that makes a line eligible. */
    private static final RestrictedTender SOCIAL_CARD =
            new RestrictedTender("CAS", ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE, "");

    /** The date of a document as the signature and the QR state it. */
    private static final DateTimeFormatter QR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** The moment a document was recorded, as the signature states it. */
    private static final DateTimeFormatter ENTRY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** What a request answers when it issued its documents. */
    private static final String NO_ERROR = null;

    /** Whether the store issues the Portuguese documents, and the ATCUD codes. */
    @Inject
    PosSettingsService posSettingsService;

    /** The fiscal sequences, per type, register, point of sale and year. */
    @Inject
    TicketNumberService ticketNumberService;

    /** The sale under way, and the invoice its customer may have asked for. */
    @Inject
    PosState state;

    /**
     * Declares again to the store node a sale whose documents changed after it
     * was pushed (BO-02-04-19): the credit note, the invoice and the receipt
     * issued later travel with their sale, which the store node upserts.
     */
    @Inject
    com.intermarche.pos.service.sync.register.SyncOutboxService syncOutboxService;

    /** The register issuing the documents. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** The point of sale the register belongs to. */
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> pdvNumber;

    /**
     * The register's signing key, Base64 PKCS#8 — the one whose public half was
     * communicated with the certification. Absent, a key is generated for the
     * life of the process, which suits a development register and nothing else.
     */
    @ConfigProperty(name = "pos.fiscal.pt.private-key")
    Optional<String> privateKey;

    /** The signing key, resolved on first use. */
    private PrivateKey signingKey;

    /**
     * The outcome of an operator's request on a past document.
     *
     * @param error the message key of the refusal, null when the documents were issued
     * @param documents the documents issued, in printing order
     */
    public record Outcome(String error, List<FiscalDocument> documents) {
    }

    /**
     * Whether the register issues the Portuguese documents.
     *
     * @return true when the store administers them
     */
    public boolean enabled() {
        return posSettingsService.ptDocuments();
    }

    /**
     * Issues the documents stating the sale under way, once it is settled
     * (LC-08-01-05, LC-08-01-06, LC-08-04-18, -19).
     *
     * <p>Idempotent: a sale already stated answers its documents, so the first
     * print, a reprint and the closing all read the same ones. Only the sale the
     * register is closing is stated — a reprint of an older sale never issues
     * a document after the fact — and only once its payments cover its total.
     * A sale already closed is queued again for the store node once stated, so
     * the node receives its documents even when the ticket left before they
     * were sealed; a sale stated before its closing (its first paper) is not:
     * the closing queues it with them (BO-05-01-05, BO-11-04-02: the SAF-T at
     * the flow reads them there).
     *
     * @param ticketId the sale
     * @return the documents stating it, in part order, empty when it states none
     */
    @Transactional
    public List<FiscalDocument> issueSaleDocuments(Long ticketId) {
        LOGGER.info("Entering method issueSaleDocuments with ticketId: " + ticketId);
        if (!enabled() || state.trainingMode || ticketId == null
                || !ticketId.equals(state.payment.ticketDbId)) {
            LOGGER.info("Exiting method issueSaleDocuments");
            return List.of();
        }
        List<FiscalDocument> issued = FiscalDocument.activeForTicket(ticketId);
        if (!issued.isEmpty()) {
            LOGGER.info("Exiting method issueSaleDocuments");
            return issued;
        }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null || ticket.totalIncludingTax.signum() < 0
                || paid(ticket).compareTo(ticket.totalIncludingTax) < 0) {
            LOGGER.info("Exiting method issueSaleDocuments");
            return List.of();
        }
        TicketState.InvoiceRequest request = state.ticket.invoiceRequest;
        FiscalDocument.Type type = request == null ? FiscalDocument.Type.FS : FiscalDocument.Type.FT;
        List<TicketLine> sold = new ArrayList<>();
        List<TicketLine> eligible = new ArrayList<>();
        for (TicketLine line : ticket.lines) {
            if (!line.cancelled) {
                sold.add(line);
                if (SOCIAL_CARD.covers(line.restrictedTenders)) {
                    eligible.add(line);
                }
            }
        }
        BigDecimal socialCard = BigDecimal.ZERO;
        for (TicketPayment payment : ticket.payments) {
            if (payment instanceof SocialCardPayment) {
                socialCard = socialCard.add(payment.amount);
            }
        }
        List<FiscalDocument> documents = new ArrayList<>();
        if (socialCard.signum() > 0 && !eligible.isEmpty() && eligible.size() < sold.size()) {
            // LC-08-01-06: the eligible lines the card paid on the first document,
            // the rest of the sale on the second, both of this transaction.
            VatBreakdown first = breakdown(eligible);
            BigDecimal firstGross = first.getTotalIncludingTax().min(ticket.totalIncludingTax);
            List<TicketLine> others = new ArrayList<>(sold);
            others.removeAll(eligible);
            String[] shares = shares(ticket.payments, firstGross);
            documents.add(seal(saleDocument(type, ticket, request, 1, uids(eligible), shares[0],
                    firstGross, first.getTotalVat()), first));
            documents.add(seal(saleDocument(type, ticket, request, 2, uids(others), shares[1],
                    ticket.totalIncludingTax.subtract(firstGross),
                    ticket.totalVat.subtract(first.getTotalVat())), breakdown(others)));
        } else {
            documents.add(seal(saleDocument(type, ticket, request, 0, null, null,
                    ticket.totalIncludingTax, ticket.totalVat), breakdown(sold)));
        }
        // BO-05-01-05, BO-11-04-02: a closed sale was already queued, and the
        // outbox may have shipped it before these documents existed; queued
        // once more (the outbox ships a sale queued twice in one batch once),
        // the store node receives them for the SAF-T export and the SAF-T at
        // the flow. A sale not yet closed travels with them at its closing.
        if (ticket.status == Ticket.TicketStatus.CLOSED) {
            syncOutboxService.enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET, ticketId);
        }
        LOGGER.info("Exiting method issueSaleDocuments");
        return documents;
    }

    /**
     * Issues the credit note stating a return (LC-05-07-03).
     *
     * <p>Idempotent: a return already stated answers its credit note. The note
     * names the documents of the returned sale it corrects, and the customer
     * they stated.
     *
     * @param refundId the return
     * @return the credit note, or null when the register issues no Portuguese
     *         document or the return cannot be read
     */
    @Transactional
    public FiscalDocument issueCreditNoteForRefund(Long refundId) {
        LOGGER.info("Entering method issueCreditNoteForRefund with refundId: " + refundId);
        if (!enabled()) {
            LOGGER.info("Exiting method issueCreditNoteForRefund");
            return null;
        }
        FiscalDocument existing = FiscalDocument.forRefund(refundId);
        if (existing != null) {
            LOGGER.info("Exiting method issueCreditNoteForRefund");
            return existing;
        }
        Refund refund = Refund.findById(refundId);
        if (refund == null) {
            LOGGER.info("Exiting method issueCreditNoteForRefund");
            return null;
        }
        VatBreakdown breakdown = new VatBreakdown(posSettingsService.calcRoundingMode());
        for (RefundLine line : refund.lines) {
            breakdown.add(line.vatRate, line.price.multiply(line.quantity).setScale(2, RoundingMode.HALF_UP));
        }
        FiscalDocument note = new FiscalDocument();
        note.documentType = FiscalDocument.Type.NC;
        note.refundId = refund.id;
        note.ticketId = refund.originalTicketId;
        StringJoiner sources = new StringJoiner(", ");
        List<FiscalDocument> corrected = refund.originalTicketId == null
                ? List.of() : correctedDocuments(refund);
        for (FiscalDocument document : corrected) {
            sources.add(document.number);
        }
        note.sourceNumber = corrected.isEmpty() ? null : sources.toString();
        if (!corrected.isEmpty()) {
            FiscalDocument stated = corrected.get(0);
            note.customerTaxId = stated.customerTaxId;
            note.customerName = stated.customerName;
            note.customerAddress = stated.customerAddress;
        }
        note.grossTotal = refund.totalAmount.abs();
        note.taxTotal = refund.totalVat == null ? breakdown.getTotalVat() : refund.totalVat.abs();
        FiscalDocument sealed = seal(note, breakdown);
        // BO-05-01-05: the refund is queued in the very transaction that seals
        // its credit note, so it travels with it: no second queueing.
        LOGGER.info("Exiting method issueCreditNoteForRefund");
        return sealed;
    }

    /**
     * The documents of the returned sale a credit note corrects (LC-05-07-03): of
     * a sale stated on two documents, those stating a returned line; when no
     * returned line can be traced to one of them, every document of the sale.
     *
     * @param refund the return, its original sale known
     * @return the corrected documents, empty when the sale states none
     */
    private static List<FiscalDocument> correctedDocuments(Refund refund) {
        List<FiscalDocument> stated = FiscalDocument.activeForTicket(refund.originalTicketId);
        Ticket sale = Ticket.findById(refund.originalTicketId);
        List<FiscalDocument> corrected = new ArrayList<>();
        for (FiscalDocument document : stated) {
            if (statesAReturnedLine(document, sale, refund)) {
                corrected.add(document);
            }
        }
        return corrected.isEmpty() ? stated : corrected;
    }

    /**
     * Tells whether a document states one of the lines a return gives back.
     *
     * @param document a document of the returned sale
     * @param sale the returned sale, null when it cannot be read
     * @param refund the return
     * @return true when a returned line is stated on the document
     */
    private static boolean statesAReturnedLine(FiscalDocument document, Ticket sale, Refund refund) {
        if (sale == null) {
            return false;
        }
        for (RefundLine returned : refund.lines) {
            for (TicketLine line : sale.lines) {
                if (line.id != null && line.id.equals(returned.originalLineId) && document.covers(line)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turns a simplified invoice into an invoice after the sale (LC-08-04-21): the
     * simplified invoice the operator names — its day, its register, its rank — is
     * cancelled by a credit note, then the invoice states the same sale for the
     * customer.
     *
     * @param day the day the simplified invoice was issued
     * @param terminal the register that issued it
     * @param rank its rank in its series
     * @param customer the customer the invoice states
     * @return the credit note and the invoice, or the refusal
     */
    @Transactional
    public Outcome convertToInvoice(LocalDate day, String terminal, long rank,
            TicketState.InvoiceRequest customer) {
        LOGGER.info("Entering method convertToInvoice with day: " + day + ", terminal: " + terminal + ", rank: " + rank);
        FiscalDocument simplified = FiscalDocument.findIssued(FiscalDocument.Type.FS, terminal, day, rank);
        String refusal = refusalOf(simplified);
        if (refusal != null) {
            LOGGER.info("Exiting method convertToInvoice");
            return new Outcome(refusal, List.of());
        }
        VatBreakdown breakdown = breakdownOf(simplified);
        FiscalDocument note = copyOf(simplified, FiscalDocument.Type.NC);
        note.customerTaxId = simplified.customerTaxId;
        note.taxTotal = simplified.taxTotal;
        FiscalDocument sealedNote = seal(note, breakdown);
        simplified.cancelled = true;
        FiscalDocument invoice = copyOf(simplified, FiscalDocument.Type.FT);
        invoice.customerTaxId = customer.taxId();
        invoice.customerName = customer.name();
        invoice.customerAddress = customer.address();
        invoice.taxTotal = simplified.taxTotal;
        FiscalDocument sealedInvoice = seal(invoice, breakdown);
        syncOutboxService.enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET,
                simplified.ticketId);
        LOGGER.info("Exiting method convertToInvoice");
        return new Outcome(NO_ERROR, List.of(sealedNote, sealedInvoice));
    }

    /**
     * Issues the receipt of an invoice (LC-08-04-22): the invoice the operator
     * names — its day, its register, its rank — gets its recibo, once.
     *
     * @param day the day the invoice was issued
     * @param terminal the register that issued it
     * @param rank its rank in its series
     * @return the receipt, or the refusal
     */
    @Transactional
    public Outcome issueReceipt(LocalDate day, String terminal, long rank) {
        LOGGER.info("Entering method issueReceipt with day: " + day + ", terminal: " + terminal + ", rank: " + rank);
        FiscalDocument invoice = FiscalDocument.findIssued(FiscalDocument.Type.FT, terminal, day, rank);
        String refusal = refusalOf(invoice);
        if (refusal == null && !FiscalDocument.referring(FiscalDocument.Type.RC, invoice.number).isEmpty()) {
            refusal = "fiscalpt.reciboDejaEmis";
        }
        if (refusal != null) {
            LOGGER.info("Exiting method issueReceipt");
            return new Outcome(refusal, List.of());
        }
        FiscalDocument receipt = copyOf(invoice, FiscalDocument.Type.RC);
        receipt.customerTaxId = invoice.customerTaxId;
        receipt.customerName = invoice.customerName;
        receipt.customerAddress = invoice.customerAddress;
        receipt.taxTotal = BigDecimal.ZERO;
        FiscalDocument sealed = seal(receipt, new VatBreakdown());
        syncOutboxService.enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET,
                invoice.ticketId);
        LOGGER.info("Exiting method issueReceipt");
        return new Outcome(NO_ERROR, List.of(sealed));
    }

    /**
     * The lines a document states, in the sale's order.
     *
     * @param document the document
     * @return the lines, empty when its sale cannot be read
     */
    public List<TicketLine> linesOf(FiscalDocument document) {
        Ticket ticket = document.ticketId == null ? null : Ticket.findById(document.ticketId);
        List<TicketLine> lines = new ArrayList<>();
        if (ticket != null) {
            for (TicketLine line : ticket.lines) {
                if (document.covers(line)) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Why a past document cannot be acted upon.
     *
     * @param document the document the operator named, null when none matched
     * @return the message key of the refusal, null when it can
     */
    private static String refusalOf(FiscalDocument document) {
        if (document == null) {
            return "fiscalpt.introuvable";
        }
        return document.cancelled ? "fiscalpt.dejaAnnule" : null;
    }

    /**
     * The VAT ventilation of the lines a document states.
     *
     * @param document the document
     * @return the ventilation
     */
    private VatBreakdown breakdownOf(FiscalDocument document) {
        return breakdown(linesOf(document));
    }

    /**
     * Starts a document stating what another one states: the same sale, the
     * same part, the same total.
     *
     * @param source the document referred to
     * @param type the new document's type
     * @return the new document, not yet sealed
     */
    private static FiscalDocument copyOf(FiscalDocument source, FiscalDocument.Type type) {
        FiscalDocument copy = new FiscalDocument();
        copy.documentType = type;
        copy.ticketId = source.ticketId;
        copy.part = source.part;
        copy.lineUids = source.lineUids;
        copy.paymentShares = source.paymentShares;
        copy.sourceNumber = source.number;
        copy.grossTotal = source.grossTotal;
        return copy;
    }

    /**
     * Starts a document stating a sale, or one part of it.
     *
     * @param type FS or FT
     * @param ticket the sale
     * @param request the invoice the customer asked for, null for a simplified invoice
     * @param part the part, zero for the whole sale
     * @param lineUids the lines of the part, null for the whole sale
     * @param shares the payment shares of the part, null for the whole sale
     * @param gross the gross total stated
     * @param tax the tax stated
     * @return the document, not yet sealed
     */
    private FiscalDocument saleDocument(FiscalDocument.Type type, Ticket ticket,
            TicketState.InvoiceRequest request, int part, String lineUids, String shares,
            BigDecimal gross, BigDecimal tax) {
        FiscalDocument document = new FiscalDocument();
        document.documentType = type;
        document.ticketId = ticket.id;
        document.part = part;
        document.lineUids = lineUids;
        document.paymentShares = shares;
        if (request == null) {
            // LC-08-01-05: the simplified invoice states the NIF the sale carries.
            document.customerTaxId = ticket.customerTaxId == null || ticket.customerTaxId.isBlank()
                    ? null : ticket.customerTaxId;
        } else {
            document.customerTaxId = request.taxId();
            document.customerName = request.name();
            document.customerAddress = request.address();
        }
        document.grossTotal = gross;
        document.taxTotal = tax;
        return document;
    }

    /**
     * Numbers, signs and records a document (LC-08-04-23).
     *
     * @param document the document, its type, sale and totals set
     * @param breakdown the VAT ventilation its QR code states
     * @return the recorded document
     */
    private FiscalDocument seal(FiscalDocument document, VatBreakdown breakdown) {
        document.issuedAt = LocalDateTime.now().withNano(0);
        document.terminalId = terminalId;
        String number = ticketNumberService.nextDocumentNumber(
                DocumentTemplate.DocumentType.valueOf(document.documentType.name()),
                pdvNumber.orElse(""), document.issuedAt.getYear());
        document.number = number;
        int slash = number.lastIndexOf('/');
        document.series = number.substring(number.lastIndexOf(' ') + 1, slash);
        document.rank = Long.parseLong(number.substring(slash + 1));
        document.atcud = posSettingsService.atcudCode(document.documentType.name()) + "-" + document.rank;
        // BO-10-06-01/02: the SAF-T identifier of its type, as administered today.
        document.saftDocumentId = posSettingsService.saftDocumentId(document.documentType.name());
        FiscalDocument previous = FiscalDocument.lastOf(document.documentType, document.series);
        document.previousHash = previous == null ? "" : previous.hash;
        document.hash = sign(String.join(";", document.issuedAt.toLocalDate().toString(),
                document.issuedAt.format(ENTRY_DATE), number, amount(document.grossTotal),
                document.previousHash));
        document.qrPayload = qrPayload(document, breakdown);
        document.persist();
        return document;
    }

    /**
     * The payload of the fiscal QR code (Portaria 195/2020): issuer, customer,
     * document, ATCUD, the bases and taxes per rate class, the totals, the four
     * signature characters and the certificate.
     *
     * @param document the numbered and signed document
     * @param breakdown its VAT ventilation
     * @return the payload
     */
    String qrPayload(FiscalDocument document, VatBreakdown breakdown) {
        List<Store> stores = Store.listAll();
        String issuer = stores.isEmpty() ? "" : digits(stores.get(0).vatNumber);
        // LC-08-06-11: the customer's number and country, the final consumer's
        // 999999990 in Portugal when the document names nobody.
        boolean finalConsumer = FiscalDocument.isFinalConsumer(document.customerTaxId);
        String customer = finalConsumer ? FINAL_CONSUMER : FiscalDocument.numberOf(document.customerTaxId);
        StringBuilder qr = new StringBuilder();
        qr.append("A:").append(issuer)
                .append("*B:").append(customer)
                .append("*C:").append(finalConsumer ? "PT" : FiscalDocument.countryOf(document.customerTaxId))
                .append("*D:").append(document.documentType.name())
                .append("*E:N*F:").append(document.issuedAt.format(QR_DATE))
                .append("*G:").append(document.number)
                .append("*H:").append(document.atcud);
        if (document.documentType == FiscalDocument.Type.RC) {
            qr.append("*I1:0");
        } else {
            qr.append("*I1:PT");
            BigDecimal[] bases = new BigDecimal[4];
            BigDecimal[] taxes = new BigDecimal[4];
            for (VatBreakdown.Bucket bucket : breakdown.getBuckets()) {
                int rateClass = rateClass(bucket.rate);
                bases[rateClass] = add(bases[rateClass], bucket.totalExcludingTax);
                taxes[rateClass] = add(taxes[rateClass], bucket.vatAmount);
            }
            if (bases[0] != null) {
                qr.append("*I2:").append(amount(bases[0]));
            }
            for (int rateClass = 1; rateClass < 4; rateClass++) {
                if (bases[rateClass] != null) {
                    qr.append("*I").append(rateClass * 2 + 1).append(':').append(amount(bases[rateClass]))
                            .append("*I").append(rateClass * 2 + 2).append(':').append(amount(taxes[rateClass]));
                }
            }
        }
        qr.append("*N:").append(amount(document.taxTotal))
                .append("*O:").append(amount(document.grossTotal))
                .append("*Q:").append(document.hashCharacters())
                .append("*R:").append(certificate(posSettingsService.fiscalCertificateNumber()));
        return qr.toString();
    }

    /**
     * The certificate number of the software as a Portuguese document states it:
     * the administered number without the {@code /AT} the mention adds itself.
     *
     * @param administered the administered certification number, possibly blank
     * @return the number, {@code 0} when none is administered
     */
    public static String certificate(String administered) {
        String number = administered == null ? "" : administered.trim();
        if (number.toUpperCase(java.util.Locale.ROOT).endsWith("/AT")) {
            number = number.substring(0, number.length() - 3).trim();
        }
        return number.isEmpty() ? "0" : number;
    }

    /**
     * The Portuguese rate class of a VAT rate: exempt, reduced, intermediate or
     * normal.
     *
     * @param rate the rate, as a fraction
     * @return 0 exempt, 1 reduced, 2 intermediate, 3 normal
     */
    private static int rateClass(BigDecimal rate) {
        if (rate.signum() == 0) {
            return 0;
        }
        if (rate.compareTo(new BigDecimal("0.10")) < 0) {
            return 1;
        }
        return rate.compareTo(new BigDecimal("0.18")) < 0 ? 2 : 3;
    }

    /**
     * Adds an amount to a running total that may not have started.
     *
     * @param total the running total, null when nothing was added yet
     * @param value the amount to add
     * @return the new total
     */
    private static BigDecimal add(BigDecimal total, BigDecimal value) {
        return total == null ? value : total.add(value);
    }

    /**
     * Signs the chained data of a document, SHA-1 with RSA, as the certification
     * rules ask.
     *
     * @param data the data
     * @return the signature, Base64
     */
    private String sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(signingKey());
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature du document fiscal impossible", e);
        }
    }

    /**
     * The register's signing key: the administered one, or a key generated for
     * the life of the process.
     *
     * @return the key
     * @throws GeneralSecurityException when the administered key cannot be read
     */
    private synchronized PrivateKey signingKey() throws GeneralSecurityException {
        if (signingKey == null) {
            if (privateKey.isPresent()) {
                signingKey = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.get().trim())));
            } else {
                LOGGER.warn("Aucune cle de signature fiscale administree (pos.fiscal.pt.private-key) : cle de developpement generee");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(1024);
                signingKey = generator.generateKeyPair().getPrivate();
            }
        }
        return signingKey;
    }

    /**
     * What the payments of a sale add up to.
     *
     * @param ticket the sale
     * @return the total paid
     */
    private static BigDecimal paid(Ticket ticket) {
        BigDecimal paid = BigDecimal.ZERO;
        for (TicketPayment payment : ticket.payments) {
            paid = paid.add(payment.amount);
        }
        return paid;
    }

    /**
     * Shares the payments of a sale between its two documents (LC-08-01-06): the
     * social card pays the first, the other payments fill what it left of the
     * first in their order, and whatever remains of each payment goes to the
     * second.
     *
     * @param payments the payments, in their order on the sale
     * @param firstGross the total of the first document
     * @return the shares of the first and of the second document
     */
    private static String[] shares(List<TicketPayment> payments, BigDecimal firstGross) {
        BigDecimal[] first = new BigDecimal[payments.size()];
        BigDecimal left = firstGross;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < payments.size(); index++) {
                TicketPayment payment = payments.get(index);
                if ((payment instanceof SocialCardPayment) == (pass == 0)) {
                    BigDecimal taken = payment.amount.min(left).max(BigDecimal.ZERO);
                    first[index] = taken;
                    left = left.subtract(taken);
                }
            }
        }
        StringJoiner firstShares = new StringJoiner(";");
        StringJoiner secondShares = new StringJoiner(";");
        for (int index = 0; index < payments.size(); index++) {
            BigDecimal rest = payments.get(index).amount.subtract(first[index]);
            if (first[index].signum() != 0) {
                firstShares.add(index + ":" + first[index].toPlainString());
            }
            if (rest.signum() != 0) {
                secondShares.add(index + ":" + rest.toPlainString());
            }
        }
        return new String[] {firstShares.toString(), secondShares.toString()};
    }

    /**
     * The uids of lines, as a part records them.
     *
     * @param lines the lines
     * @return their uids, comma separated
     */
    private static String uids(List<TicketLine> lines) {
        StringJoiner uids = new StringJoiner(",");
        for (TicketLine line : lines) {
            uids.add(line.lineUid);
        }
        return uids.toString();
    }

    /**
     * The VAT ventilation of lines, cut by the shop's rounding rule (BO-10-02-18)
     * like the ticket the document states.
     *
     * @param lines the lines
     * @return the ventilation
     */
    private VatBreakdown breakdown(List<TicketLine> lines) {
        VatBreakdown breakdown = new VatBreakdown(posSettingsService.calcRoundingMode());
        for (TicketLine line : lines) {
            breakdown.add(line.vatRate, line.totalPrice);
        }
        return breakdown;
    }

    /**
     * Keeps only the digits of a tax number.
     *
     * @param taxId the tax number, possibly null or prefixed by its country
     * @return its digits, empty when there are none
     */
    private static String digits(String taxId) {
        return taxId == null ? "" : taxId.replaceAll("[^0-9]", "");
    }

    /**
     * Writes an amount as the signature and the QR state it: two decimals, a dot.
     *
     * @param value the amount
     * @return the amount as text
     */
    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
