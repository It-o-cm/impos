package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.payment.NexoHubClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The DEMATERIALISED ARTICLES of a sale and the payment hub that activates them
 * (LC-02-09-01 to -03): a gift card, a gift box, a phone top-up sold by a code
 * and not by a packet.
 *
 * <p>The article is rung, its unique code is scanned, and the code is sent to
 * the hub in a Nexo {@code StoredValue Activate} request for the article's amount
 * — the price the referential carries, or the one the operator keyed for an
 * article sold at a chosen amount. The line stays only if the hub accepts; the
 * activation code is then shown under it. A line cancelled in the transaction in
 * progress is first reversed on the hub ({@code StoredValue Reverse}), and so is
 * every activated line of an abandoned ticket: a refusal keeps the line, because
 * a card the hub still holds active must not leave the till unpaid. Once the sale
 * is closed, every activated article gets its activation ticket.
 *
 * <p>Without an administered hub, and always in training mode, nothing is sent:
 * the code is stored on the line as it always was, and no activation ticket is
 * printed.
 */
@ApplicationScoped
public class DematerialisedService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DematerialisedService.class);

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** The payment hub. */
    @Inject
    NexoHubClient nexoHubClient;

    /** When the NEXO exchange happens during the sale (BO-11-03-04). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Prints the activation tickets. */
    @Inject
    TicketPrinterService ticketPrinterService;

    /**
     * Whether the hub takes part in this sale.
     *
     * @param state the current POS state
     * @return true when a hub is administered and the register is not training
     */
    public boolean hubActive(PosState state) {
        return nexoHubClient.isConfigured() && !state.trainingMode;
    }

    /**
     * Whether the code just scanned is activated through the hub
     * (BO-03-02-37): the hub takes part in the sale, and the prepaid range the
     * code falls in — if any — does not say the register handles it locally.
     *
     * @param state the current POS state
     * @param code the activation code scanned
     * @return true when the activation goes to the hub
     */
    public boolean thirdPartyActivation(PosState state, String code) {
        // BO-03-02-37: the administered TYPE of prepaid card names the
        // protocol first; without one, the coupon range still answers.
        com.intermarche.pos.domain.payment.PrepaidCardType type = prepaidType(code);
        if (type != null) {
            return hubActive(state) && type.isNexo();
        }
        return hubActive(state)
                && !com.intermarche.pos.domain.barcode.CouponType.PROTOCOL_LOCAL.equals(protocolOf(code));
    }

    /**
     * Whether the NEXO activation is deferred from the scan to the entry into
     * payment (BO-11-03-04).
     *
     * @return true when the store administers the exchange at payment
     */
    public boolean activationAtPayment() {
        return posSettingsService.nexoActivationAtPayment();
    }

    /**
     * Activates, at the entry into payment, every dematerialised article of the
     * sale still awaiting its NEXO activation (BO-11-03-04): the lines carrying
     * an activation code, not activated yet, not cancelled, and that the hub
     * activates. A refusal is shown and names the line, which the caller strikes
     * before any settlement — money is never taken for a product the provider
     * did not activate.
     *
     * @param state the current POS state
     * @return the uids of the refused lines, empty when every one was activated
     */
    public java.util.List<String> activatePending(PosState state) {
        LOGGER.info("Entering method activatePending");
        java.util.List<String> refused = new java.util.ArrayList<>();
        for (TicketState.TicketItem line : new java.util.ArrayList<>(state.ticket.items)) {
            if (line.cancelled || line.returnLine || line.activationCode == null
                    || line.activationReference != null || !thirdPartyActivation(state, line.activationCode)) {
                continue;
            }
            String refusal = activate(state, line);
            if (refusal != null) {
                state.ticket.setError(refusal);
                refused.add(line.uid);
            }
        }
        LOGGER.info("Exiting method activatePending");
        return refused;
    }

    /**
     * Why the code just scanned may not be activated (BO-03-02-37): its
     * administered type of prepaid card forbids the activation at the sale.
     *
     * @param code the activation code scanned
     * @return the refusal shown to the operator, or null when the activation may go on
     */
    public String activationRefusal(String code) {
        com.intermarche.pos.domain.payment.PrepaidCardType type = prepaidType(code);
        return type != null && !type.activationAllowed
                ? i18n.format("op.activationNonAutorisee", type.label) : null;
    }

    /**
     * Reads the administered type of prepaid card a code belongs to
     * (BO-03-02-37), a referential that cannot be read naming none.
     *
     * @param code the code
     * @return the type, or null
     */
    private com.intermarche.pos.domain.payment.PrepaidCardType prepaidType(String code) {
        try {
            return com.intermarche.pos.domain.payment.PrepaidCardType.findFor(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the protocol of the prepaid range a code falls in, a referential
     * that cannot be read naming none.
     *
     * @param code the code
     * @return the protocol, or null
     */
    private String protocolOf(String code) {
        try {
            return com.intermarche.pos.domain.barcode.CouponType.protocolOf(code);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Activates the article whose code was just scanned (LC-02-09-01).
     *
     * @param state the current POS state
     * @param line the line carrying the scanned code
     * @return null when the article is activated, the refusal shown to the
     *         operator otherwise — the caller then cancels the line
     */
    public String activate(PosState state, TicketState.TicketItem line) {
        LOGGER.info("Entering method activate with line: " + line.uid);
        BigDecimal amount = line.unitPrice == null ? BigDecimal.ZERO : line.unitPrice;
        if (amount.signum() <= 0) {
            LOGGER.info("Exiting method activate (no amount)");
            return i18n.get("op.activationSansMontant");
        }
        // BO-03-02-37: the provider of the card's administered type is named
        // to the hub when the article itself names none.
        if (line.activationProvider == null) {
            com.intermarche.pos.domain.payment.PrepaidCardType type = prepaidType(line.activationCode);
            line.activationProvider = type == null ? null : type.provider;
        }
        NexoHubClient.HubResult result = nexoHubClient.activate(reference(state, line.uid),
                line.activationProvider, line.ean, line.activationCode, amount);
        if (!result.success()) {
            LOGGER.info("Exiting method activate (refused)");
            return refusal("op.activationRefusee", result, line.activationProvider);
        }
        line.activationReference = result.transactionId();
        line.activationApproval = result.approvalCode();
        if (line.activationProvider == null) {
            line.activationProvider = result.provider();
        }
        BigDecimal after = result.balance() == null ? amount : result.balance();
        line.activationBalanceAfter = after;
        line.activationBalanceBefore = after.subtract(amount).max(BigDecimal.ZERO);
        // BO-03-08-06 / -07: what the shop wrote for the provider's answer —
        // on the screen in place of the register's notice, and on the
        // activation ticket where the register prints nothing of its own.
        line.activationTicketText = result.ticketText(line.activationProvider);
        String shown = result.screenText(line.activationProvider);
        if (shown == null) {
            shown = i18n.format("op.articleActive", line.activationCode, money(after));
        }
        state.ticket.setNotice(shown);
        LOGGER.info("Exiting method activate (activated)");
        return null;
    }

    /**
     * Reverses the activation of a line about to be cancelled (LC-02-09-02).
     *
     * @param state the current POS state
     * @param line the line to cancel
     * @return true when nothing stands in the way of the cancellation: the line
     *         was never activated, or the hub reversed its activation
     */
    public boolean reverse(PosState state, TicketState.TicketItem line) {
        LOGGER.info("Entering method reverse with line: " + line.uid);
        if (line.activationReference == null) {
            LOGGER.info("Exiting method reverse (not activated)");
            return true;
        }
        NexoHubClient.HubResult result = nexoHubClient.reverseStoredValue(
                reference(state, line.uid), line.activationReference);
        if (!result.success()) {
            String refusal = refusal("op.annulationActivationRefusee", result, line.activationProvider);
            state.ticket.setError(refusal);
            LOGGER.info("Exiting method reverse (refused)");
            return false;
        }
        line.activationReference = null;
        state.ticket.setNotice(i18n.format("op.activationAnnulee", line.activationCode));
        LOGGER.info("Exiting method reverse (reversed)");
        return true;
    }

    /**
     * Reverses every activation of a ticket about to be abandoned (LC-02-09-02).
     *
     * @param state the current POS state
     * @return true when every activated line was reversed
     */
    public boolean reverseAll(PosState state) {
        LOGGER.info("Entering method reverseAll");
        for (TicketState.TicketItem item : state.ticket.items) {
            if (!reverse(state, item)) {
                LOGGER.info("Exiting method reverseAll (refused)");
                return false;
            }
        }
        LOGGER.info("Exiting method reverseAll");
        return true;
    }

    /**
     * Prints the activation ticket of every article the closed sale activated,
     * after its receipt (LC-02-09-03).
     *
     * @param state the current POS state, before it is cleared
     * @param ticketNumber the number of the closed sale, or null
     * @return the number of activation tickets printed
     */
    public int printActivationTickets(PosState state, String ticketNumber) {
        LOGGER.info("Entering method printActivationTickets");
        int printed = 0;
        for (TicketState.TicketItem item : state.ticket.items) {
            if (item.activationReference == null) {
                continue;
            }
            ticketPrinterService.printActivationTicket(ticketNumber, item.label, item.activationCode,
                    item.unitPrice, item.activationBalanceBefore, item.activationBalanceAfter,
                    item.activationReference, item.activationProvider, item.activationApproval,
                    item.activationTicketText);
            printed++;
        }
        LOGGER.info("Exiting method printActivationTickets (" + printed + ")");
        return printed;
    }

    /**
     * The register's reference of an operation on a line: the register and the
     * line, which the hub keeps beside its own transaction identifier.
     *
     * @param state the current POS state
     * @param uid the line uid
     * @return the reference
     */
    private static String reference(PosState state, String uid) {
        return (state.payment.ticketDbId == null ? "" : state.payment.ticketDbId + "-") + uid;
    }

    /**
     * The operator's message of a hub refusal.
     *
     * @param key the message pattern, taking the hub's reason
     * @param result the hub's answer
     * @param provider the provider the request went to, or null when unknown
     * @return the message
     */
    private String refusal(String key, NexoHubClient.HubResult result, String provider) {
        if (result.unavailable()) {
            return i18n.get("op.hubIndisponible");
        }
        // BO-03-08-06: the shop's sentence for the provider's code — the
        // « ERROR CODE 4 ITEM » the cashier reads as « Produit déjà activé ».
        String administered = result.screenText(provider);
        if (administered != null) {
            return administered;
        }
        return i18n.format(key, result.message() == null ? result.errorCondition() : result.message());
    }

    /**
     * An amount written the French way, two decimals.
     *
     * @param amount the amount
     * @return the written amount
     */
    private static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRANCE));
        return format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
