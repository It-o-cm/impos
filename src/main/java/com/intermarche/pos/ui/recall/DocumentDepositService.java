package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.payment.DepositVat;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * The DEPOSIT taken at the till on a quote or an order the commercial management
 * tool issued (LC-06-02-04, -07, LC-08-04-15), recalled by its code and waiting
 * for the cashier's choice.
 *
 * <p>A deposit is the movement the register already knows for an order — a
 * {@code CUSTOMER_DEPOSIT} carrying the document's code, which the store node
 * imputes to the document — ventilated per VAT rate on the document's own total,
 * told to the node so the document is updated for the back office and the tool
 * that issued it, and printed: the deposit slip, and the deposit invoice where
 * the store asks for one. Several deposits may follow one another on the same
 * document; each is ventilated on its own and added to what the document carries.
 */
@ApplicationScoped
public class DocumentDepositService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DocumentDepositService.class);

    /** The outcome of a deposit attempt, one per refusal the screen names. */
    public enum Outcome {

        /** The deposit was collected. */
        COLLECTED,

        /** No document waits for a deposit. */
        NO_DOCUMENT,

        /** No cash session is open on this register. */
        NO_SESSION,

        /** Training mode touches no real document. */
        TRAINING,

        /** The document admits no deposit. */
        NOT_ALLOWED,

        /** The amount is unreadable or not strictly positive. */
        BAD_AMOUNT,

        /** The amount exceeds what remains due on the document. */
        OVER_REMAINING,

        /** The rules of the document's channel refuse it (BO-10-01). */
        REFUSED
    }

    /** Records the deposit as a CUSTOMER_DEPOSIT movement. */
    @Inject
    CashMovementService cashMovementService;

    /** Reads the open session the deposit movement belongs to. */
    @Inject
    CashSessionService cashSessionService;

    /** Prices the document as the store administers it. */
    @Inject
    TicketService ticketService;

    /** Tells the store node about the deposit. */
    @Inject
    BackOfficeDocumentClient documentClient;

    /** Prints the deposit slip and the deposit invoice. */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** Numbers the deposit invoice. */
    @Inject
    TicketNumberService ticketNumberService;

    /** The tender of the deposits (BO-10-01-27). */
    @Inject
    ChannelRules channelRules;

    /** The rules of the channel the document comes from (BO-10-01). */
    @Inject
    ChannelGate channelGate;

    /** Whether the store prints the deposit invoice. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The total of a document at the price the store administers: its lines,
     * or the total the document states when its lines price to nothing.
     *
     * @param doc the document
     * @return the document total, TTC
     */
    public BigDecimal total(RecalledDocument doc) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecalledDocument.Line line : ticketService.pricedLines(doc)) {
            total = total.add(line.total());
        }
        if (total.signum() <= 0 && doc.totalAmount != null) {
            return doc.totalAmount;
        }
        return total;
    }

    /**
     * What remains due on a document: its total less the deposits it carries.
     *
     * @param doc the document
     * @return the remaining amount, never negative
     */
    public BigDecimal remaining(RecalledDocument doc) {
        return total(doc).subtract(doc.acompteAlreadyPaid).max(BigDecimal.ZERO);
    }

    /**
     * Collects a deposit on the document waiting for the cashier's choice
     * (LC-06-02-04, -07, LC-08-04-15).
     *
     * <p>On success the deposit is recorded, the node told, the slip — and the
     * deposit invoice where administered — printed, and the waiting document
     * carries the new deposit and its VAT, so the screen shows what remains and
     * a settlement in the same visit takes the deposit back. On any refusal
     * nothing is recorded, told or printed.
     *
     * @param state the current POS state
     * @param amount the deposit, TTC, as keyed
     * @return the outcome
     */
    public Outcome collect(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method collect with amount: " + amount);
        RecalledDocument doc = state.pendingDocument;
        Outcome refusal = refusal(state, doc, amount);
        if (refusal != null) {
            LOGGER.info("Exiting method collect (" + refusal + ")");
            return refusal;
        }
        List<RecalledDocument.Line> lines = ticketService.pricedLines(doc);
        BigDecimal total = total(doc);
        VatBreakdown breakdown = new VatBreakdown();
        for (RecalledDocument.Line line : lines) {
            breakdown.add(line.vatRate(), line.total());
        }
        String vatDetail = DepositVat.compute(breakdown.getBuckets(), amount, total);
        Employee cashier = state.auth.operatorId != null ? Employee.findById(state.auth.operatorId) : null;
        // BO-10-01-27: the deposit is taken in the tender the store administers.
        cashMovementService.recordAcompte(cashSessionService.getOpenSession(), cashier, amount, doc.code,
                channelRules.commercialDepositTender());
        documentClient.recordDeposit(doc.code, amount, vatDetail);
        BigDecimal previouslyPaid = doc.acompteAlreadyPaid;
        // BO-03-03-09: the slip names the customer the document was issued for.
        ticketPrinterService.printOrderDepositTicket(doc.kind, doc.code, lines, amount, vatDetail,
                customerName(doc.customerNumber));
        if (posSettingsService.orderDepositInvoice()) {
            ticketPrinterService.printDepositInvoice(doc.kind, ticketNumberService.nextDepositInvoiceNumber(),
                    doc.code, lines, total, amount, previouslyPaid);
        }
        doc.acompteAlreadyPaid = previouslyPaid.add(amount);
        doc.depositVatDetail = DepositVat.merge(doc.depositVatDetail, vatDetail);
        state.touch();
        LOGGER.info("Exiting method collect (collected)");
        return Outcome.COLLECTED;
    }

    /**
     * Names the customer a deposit slip is printed for ({@code BO-03-03-09},
     * « Nom client ») : the business name of the account customer the document
     * was issued for, its number when this register does not know that
     * customer, nothing when the document names none.
     *
     * @param customerNumber the customer number the document carries, or null
     * @return the name printed on the slip, or the number, or null
     */
    private static String customerName(String customerNumber) {
        if (customerNumber == null || customerNumber.isBlank()) {
            return customerNumber;
        }
        com.intermarche.pos.domain.payment.AccountCustomer customer =
                com.intermarche.pos.domain.payment.AccountCustomer
                        .find("accountNumber", customerNumber.trim()).firstResult();
        return customer == null ? customerNumber : customer.companyName;
    }

    /**
     * Why a deposit cannot be collected.
     *
     * @param state the current POS state
     * @param doc the waiting document, or null
     * @param amount the deposit keyed, or null
     * @return the refusal, null when the deposit can be collected
     */
    private Outcome refusal(PosState state, RecalledDocument doc, BigDecimal amount) {
        if (doc == null || doc.code == null || doc.code.isBlank()) {
            return Outcome.NO_DOCUMENT;
        }
        if (cashSessionService.getOpenSession() == null) {
            return Outcome.NO_SESSION;
        }
        if (state.trainingMode) {
            return Outcome.TRAINING;
        }
        if (!doc.offersDeposit()) {
            return Outcome.NOT_ALLOWED;
        }
        if (channelGate.refusal(state, doc) != null) {
            return Outcome.REFUSED;
        }
        if (amount == null || amount.signum() <= 0) {
            return Outcome.BAD_AMOUNT;
        }
        return amount.compareTo(remaining(doc)) > 0 ? Outcome.OVER_REMAINING : null;
    }
}
