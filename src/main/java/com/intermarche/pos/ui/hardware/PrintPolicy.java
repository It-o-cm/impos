package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * The conditional-printing RULE (LC-08-03): what actually comes out of the
 * printer once the cashier's {@link PrintChoice} has met the administered
 * forced-printing parameters.
 * <p>
 * The rule is deliberately one place. The cashier's choice can only ever
 * REMOVE documents; the back office can only ever ADD them back:
 * <ul>
 *   <li>a document named in {@code print.forced-documents} prints whatever the
 *       choice (LC-08-03-07);</li>
 *   <li>a sale ticket carrying an article under the legal conformity guarantee
 *       prints whatever the choice (LC-08-03-09);</li>
 *   <li>a card receipt asking for the customer's signature prints whatever the
 *       choice (LC-08-03-11).</li>
 * </ul>
 * The two remaining forced rules sit OUTSIDE a closed sale — a refund's credit
 * card receipt (LC-08-03-10) and a not-completed transaction's TNA receipt
 * (LC-08-03-12) — so they are asked for on their own, by
 * {@link #isCreditCardReceiptForced()} and {@link #isTnaReceiptForced()}.
 * <p>
 * Everything here is inert while {@code print.conditional-enabled} is off: the
 * decision then names every document and the two standalone rules answer false,
 * so a register that never activates the option prints exactly as it did before
 * this rule existed.
 */
@ApplicationScoped
public class PrintPolicy {

    /** Forced-document key of the sale ticket in the administered list. */
    public static final String DOCUMENT_TICKET = "TICKET";

    /** Forced-document key of the card receipt in the administered list. */
    public static final String DOCUMENT_CARD = "CARTE";

    /** Forced-document key of the purchase voucher in the administered list. */
    public static final String DOCUMENT_VOUCHER = "BON";

    /** The back-office parameters carrying the whole rule. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * What a closed transaction prints: one flag per document the sale can
     * produce.
     *
     * @param saleTicket whether the sale ticket is printed
     * @param cardReceipt whether the card receipt is printed
     * @param voucher whether the purchase vouchers are printed
     */
    public record Decision(boolean saleTicket, boolean cardReceipt, boolean voucher) {

        /**
         * Whether the decision prints nothing at all.
         *
         * @return true when no document is printed
         */
        public boolean isEmpty() {
            return !saleTicket && !cardReceipt && !voucher;
        }
    }

    /**
     * Whether the register offers the end-of-transaction choice (LC-08-03-01).
     *
     * @return true when conditional printing is activated
     */
    public boolean isConditionalEnabled() {
        return posSettingsService.printConditionalEnabled();
    }

    /**
     * Decides what a closed transaction prints.
     *
     * @param choice the cashier's choice, or null when none was made — the
     *        standard behaviour ({@link PrintChoice#ALL}) then applies
     * @param ticket the closed ticket, or null when unavailable
     * @param cardSignatureRequired whether a card payment of this ticket asked
     *        for the customer's signature
     * @return the documents to print, never null
     */
    public Decision decide(PrintChoice choice, Ticket ticket, boolean cardSignatureRequired) {
        if (!isConditionalEnabled()) {
            return new Decision(true, true, true);
        }
        PrintChoice asked = choice != null ? choice : PrintChoice.ALL;
        List<String> forced = posSettingsService.printForcedDocuments();
        boolean saleTicket = asked.isSaleTicket()
                || forced.contains(DOCUMENT_TICKET)
                || (posSettingsService.printForceTicketGlc() && carriesLegalWarranty(ticket));
        boolean cardReceipt = asked.isCardReceipt()
                || forced.contains(DOCUMENT_CARD)
                || (posSettingsService.printForceCardSignature() && cardSignatureRequired);
        boolean voucher = asked.isVoucher()
                || forced.contains(DOCUMENT_VOUCHER);
        return new Decision(saleTicket, cardReceipt, voucher);
    }

    /**
     * Whether the card receipt of a refund — a "credit" card transaction —
     * must be printed (LC-08-03-10). Inert while conditional printing is off.
     *
     * @return true when the credit card receipt is forced
     */
    public boolean isCreditCardReceiptForced() {
        return isConditionalEnabled() && posSettingsService.printForceCardCredit();
    }

    /**
     * Whether the card receipt of a not-completed transaction must be printed
     * (LC-08-03-12). Inert while conditional printing is off.
     *
     * @return true when the TNA card receipt is forced
     */
    public boolean isTnaReceiptForced() {
        return isConditionalEnabled() && posSettingsService.printForceCardTna();
    }

    /**
     * Whether the ticket carries at least one live article under the legal
     * conformity guarantee (LC-08-03-09). A cancelled line is outside the sale
     * and never counts; the flag is read from the article itself, which the
     * line still references at print time.
     *
     * @param ticket the closed ticket, or null
     * @return true when a live line carries a GLC article
     */
    private boolean carriesLegalWarranty(Ticket ticket) {
        if (ticket == null || ticket.lines == null) {
            return false;
        }
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            if (ProductAttributes.legalWarranty(line.product)) {
                return true;
            }
        }
        return false;
    }
}
