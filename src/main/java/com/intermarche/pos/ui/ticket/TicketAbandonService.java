package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules that surround the abandon of a ticket ({@code LC-04-04-06} to
 * {@code -12}): what a partial settlement forbids, what reason the operator gives, and
 * what comes out of the printer.
 *
 * <p>THE ABANDON IS THE ONE GESTURE THAT DESTROYS A SALE, and the questionnaire asks
 * for three different things to happen around it — a settlement already taken must
 * either stop it or be named before being undone; a reason must be picked from the
 * shop's own list; and a paper trace may or may not come out. All three are administered
 * because they are shop policy, not register behaviour: the same abandon is a
 * two-second gesture in one store and a signed slip in another.
 *
 * <p>None of them touches WHAT is abandoned. The cancellation itself stays where it was
 * — the draft is marked cancelled and the state cleared — and this only decides whether
 * it may happen, under what name, and with what paper.
 */
@ApplicationScoped
public class TicketAbandonService {

    /** The register state whose abandon this service governs. */
    @Inject
    PosState state;

    /** The shop's rules: reasons, partial-settlement policy, printing. */
    @Inject
    PosSettingsService posSettingsService;

    /** The administered behaviour that refuses the abandon outright. */
    public static final String BLOCK = "BLOCK";

    /** The administered rule that never prints an abandon ticket. */
    public static final String NEVER = "NEVER";

    /** The administered rule that always prints one. */
    public static final String ALWAYS = "ALWAYS";

    /** The administered rule that leaves the printing to the operator. */
    public static final String ON_DEMAND = "ON_DEMAND";

    /**
     * The abandon reasons the shop offers ({@code LC-04-04-10}).
     *
     * @return the reasons, in administered order, empty when the shop asks for none
     */
    public List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        for (String reason : posSettingsService.abandonReasons().split(";")) {
            String trimmed = reason.trim();
            if (!trimmed.isEmpty()) {
                reasons.add(trimmed);
            }
        }
        return reasons;
    }

    /**
     * Tells whether the operator must pick a reason before abandoning.
     *
     * @return true when the shop administered at least one reason
     */
    public boolean requiresReason() {
        return !reasons().isEmpty();
    }

    /**
     * The settlements already registered on the sale, which the abandon would undo.
     *
     * @return the entries, in registration order, empty when nothing was settled
     */
    public List<PaymentState.PaymentEntry> partialPayments() {
        return new ArrayList<>(state.payment.payments);
    }

    /**
     * The total already settled on the sale.
     *
     * @return the total, zero when nothing was settled
     */
    public BigDecimal partialPaymentTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            if (entry.amount != null) {
                total = total.add(entry.amount);
            }
        }
        return total;
    }

    /**
     * Why the abandon is refused, when it is ({@code LC-04-04-06} and {@code -08}).
     *
     * <p>The two requirements are one rule seen twice: a settlement that reached an
     * external system — a card authorization, a gift card debited at a registry — and
     * one that did not are both refused where the shop administers a refusal, but the
     * operator is not told the same thing. Undoing a cash tender is a keystroke; undoing
     * an authorized card payment is a reversal on somebody else's system, and the
     * message has to say which one is in front of them.
     *
     * @return the operator-facing refusal, or null when the abandon may proceed
     */
    public String blockingReason() {
        if (!BLOCK.equalsIgnoreCase(posSettingsService.abandonPartialPayment().trim())) {
            return null;
        }
        boolean external = false;
        boolean local = false;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            if (isExternal(entry)) {
                external = true;
            } else {
                local = true;
            }
        }
        if (external) {
            return "REGLEMENT PARTIEL AVEC APPEL EXTERNE - ANNULEZ-LE D'ABORD";
        }
        if (local) {
            return "REGLEMENT PARTIEL ENREGISTRE - ANNULEZ-LE D'ABORD";
        }
        return null;
    }

    /**
     * Tells whether a settlement went through an external system
     * ({@code LC-04-04-08}).
     *
     * @param entry the registered settlement
     * @return true when undoing it means undoing something outside this register
     */
    private boolean isExternal(PaymentState.PaymentEntry entry) {
        // An authorization number is the proof that something outside this register
        // said yes — a monetics terminal, a gift-card registry — and it is carried
        // whatever the method that obtained it. The card key is checked as well
        // because a monetics payment is external even where the authorization was
        // not returned.
        if (entry.authorizationNumber != null && !entry.authorizationNumber.isBlank()) {
            return true;
        }
        String method = entry.method == null ? "" : entry.method.toUpperCase(Locale.ROOT);
        return method.equals("CARD");
    }

    /**
     * Tells whether the abandon screen must offer the printing choice
     * ({@code LC-04-04-12}, on-demand rule).
     *
     * @return true when the shop leaves the decision to the operator
     */
    public boolean printOnDemand() {
        return ON_DEMAND.equalsIgnoreCase(posSettingsService.abandonPrint().trim());
    }

    /**
     * Tells whether the abandon about to happen prints a ticket
     * ({@code LC-04-04-12}).
     *
     * @return true when a ticket must come out
     */
    public boolean printsTicket() {
        String rule = posSettingsService.abandonPrint().trim();
        if (ALWAYS.equalsIgnoreCase(rule)) {
            return true;
        }
        if (ON_DEMAND.equalsIgnoreCase(rule)) {
            return state.abandonPrintRequested;
        }
        // NEVER, and anything a store mistyped: the silent behaviour, which is the
        // one that cannot surprise a lane.
        return false;
    }

    /**
     * Records the operator's choices before the abandon is carried out.
     *
     * @param reason the reason picked, or blank when the shop asks for none
     * @param print  whether the operator asked for the abandon ticket
     * @return the operator-facing refusal, or null when the abandon may proceed
     */
    public String prepare(String reason, boolean print) {
        String blocking = blockingReason();
        if (blocking != null) {
            return blocking;
        }
        String chosen = reason == null ? "" : reason.trim();
        if (requiresReason() && chosen.isEmpty()) {
            return "MOTIF D'ABANDON OBLIGATOIRE";
        }
        // A reason the shop does not offer is refused rather than journalled: the
        // screen is a list of buttons, so anything else came from a stale page or a
        // hand-made request, and the reason is what the back-office reports count.
        if (!chosen.isEmpty() && !reasons().contains(chosen)) {
            return "MOTIF D'ABANDON INCONNU";
        }
        state.abandonReason = chosen;
        state.abandonPrintRequested = print;
        state.touch();
        return null;
    }
}
