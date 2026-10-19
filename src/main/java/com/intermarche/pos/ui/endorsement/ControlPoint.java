package com.intermarche.pos.ui.endorsement;

/**
 * THE GESTURES THE SHOP WATCHES (BO-10-02-01 to -10, -16 and -17).
 *
 * <p>Ten gestures of the sale that a shop may want to see, to forbid, or to
 * hand to a supervisor. Before this enum each of them answered for itself:
 * cancelling a line asked a supervisor because a boolean said so, a refund
 * always asked one, the quantity bounds were two numbers written in the code,
 * and cancelling a payment, opening the drawer outside a sale, exceeding a
 * ticket total, closing on parked tickets and paying by card answered to
 * nothing at all, no more than the amount keyed on a line or on a
 * settlement. The questionnaire asks the same question of the ten, so they are
 * one list and one rule.
 *
 * <p>WHAT A POINT CARRIES: the back-office key stating its level, the level it
 * falls back on when that key cannot be read — which is also the level the
 * catalogue publishes as its default, and a test holds the two together — the
 * wording the operator reads when the point speaks, and, for the four points
 * that compare a figure, the key of the threshold they compare it to. THE TWO
 * AMOUNT POINTS COMPARE IN ABSOLUTE VALUE: the questionnaire asks the ceiling
 * of an article and of a settlement to hold for sales and for returns alike,
 * and a return is the same figure with the other sign.
 *
 * <p>TWO POINTS ARE SWITCHED ON ELSEWHERE. Line cancellation and refund already
 * had an administered on/off ({@code gesture.endorsement-cancel-line},
 * {@code gesture.endorsement-refund}) and they keep it: the flag says WHETHER
 * the gesture is watched, this enum's key says WHAT HAPPENS when it is. Their
 * level is therefore never {@link ControlLevel#NONE} — a shop that wants no
 * control there turns the flag off, which is the switch it already knows.
 */
public enum ControlPoint {

    /** Cancelling a line already registered (BO-10-02-01). */
    CANCEL_LINE("control.cancel-line", ControlLevel.ENDORSE,
            "op.controlePointAnnulationLigne", null, true),

    /** Cancelling a partial payment already taken (BO-10-02-02). */
    CANCEL_PAYMENT("control.cancel-payment", ControlLevel.NONE,
            "op.controlePointAnnulationPaiement", null, true),

    /** Refunding a return (BO-10-02-03). */
    REFUND("control.refund", ControlLevel.ENDORSE,
            "op.controlePointRemboursement", null, true),

    /** Opening the drawer outside a sale (BO-10-02-04). */
    DRAWER_OUT_OF_SALE("control.drawer-out-of-sale", ControlLevel.NONE,
            "op.controlePointTiroirHorsVente", null, false),

    /** Entering a quantity above the administered ceiling (BO-10-02-06). */
    QUANTITY("control.quantity", ControlLevel.NONE,
            "op.controlePointQuantite", "control.quantity-max", true),

    /** A ticket total above the administered ceiling (BO-10-02-07). */
    TICKET_TOTAL("control.ticket-total", ControlLevel.NONE,
            "op.controlePointTotalTicket", "control.ticket-total-max", false),

    /** Closing the session while tickets are still parked (BO-10-02-08). */
    PARKED_AT_CLOSE("control.parked-at-close", ControlLevel.NONE,
            "op.controlePointAttenteCloture", null, false),

    /** Settling by card (BO-10-02-10). */
    CARD_PAYMENT("control.card-payment", ControlLevel.NONE,
            "op.controlePointPaiementCarte", null, false),

    /** A line price forced above the administered ceiling (BO-10-02-16). */
    ITEM_AMOUNT("control.item-amount", ControlLevel.NONE,
            "op.controlePointMontantArticle", "control.item-amount-max", true),

    /** A settlement amount above the administered ceiling (BO-10-02-17). */
    PAYMENT_AMOUNT("control.payment-amount", ControlLevel.NONE,
            "op.controlePointMontantReglement", "control.payment-amount-max", false);

    /** The back-office key stating this point's level. */
    private final String settingKey;

    /** The level answered when the administered value cannot be read. */
    private final ControlLevel defaultLevel;

    /** The message key naming this gesture to the operator. */
    private final String labelKey;

    /** The back-office key of the threshold compared, or null for no threshold. */
    private final String thresholdKey;

    /** Whether a supervisor can lift this point's refusal where it fires. */
    private final boolean endorsable;

    /**
     * Builds a control point.
     *
     * @param settingKey the back-office key stating its level
     * @param defaultLevel the level answered when that value cannot be read
     * @param labelKey the message key naming the gesture to the operator
     * @param thresholdKey the key of the threshold compared, null for none
     * @param endorsable whether a supervisor can lift its refusal where it fires
     */
    ControlPoint(String settingKey, ControlLevel defaultLevel, String labelKey,
            String thresholdKey, boolean endorsable) {
        this.settingKey = settingKey;
        this.defaultLevel = defaultLevel;
        this.labelKey = labelKey;
        this.thresholdKey = thresholdKey;
        this.endorsable = endorsable;
    }

    /**
     * Returns the back-office key stating this point's level.
     *
     * @return the settings key, never blank
     */
    public String getSettingKey() {
        return settingKey;
    }

    /**
     * Returns the level answered when the administered value cannot be read.
     *
     * @return the fallback level, never null
     */
    public ControlLevel getDefaultLevel() {
        return defaultLevel;
    }

    /**
     * Returns the message key naming this gesture to the operator.
     *
     * @return the message key, never blank
     */
    public String getLabelKey() {
        return labelKey;
    }

    /**
     * Returns the back-office key of the threshold this point compares.
     *
     * @return the threshold key, or null when the point compares nothing
     */
    public String getThresholdKey() {
        return thresholdKey;
    }

    /**
     * Whether this point compares a figure to an administered threshold.
     *
     * @return true when the point carries a threshold key
     */
    public boolean hasThreshold() {
        return thresholdKey != null;
    }

    /**
     * Whether a supervisor can lift this point's refusal WHERE IT FIRES.
     *
     * <p>Five of the ten fire during a sale, where the endorsement ceremony
     * exists: the screen parks the gesture, a manager keys their credential and
     * the gesture resumes — a forced price is one of them, since it is already
     * a gesture the modal knows how to replay. The five others fire where there
     * is no such ceremony — the drawer opens at the lock screen or at the Z
     * count, the ticket total is reached while articles are being registered,
     * the parked tickets are counted at closing, the card is handed to the
     * terminal, the settlement amount is keyed on the payment screen. Offering
     * an aval there would offer a button nobody can press.
     *
     * <p>So {@link ControlLevel#ENDORSE} administered on one of those four is
     * applied as {@link ControlLevel#BLOCK}, by {@code EndorsementService.check}
     * and nowhere else: a shop that administers it gets a refusal, not a
     * ceremony that never comes.
     *
     * @return true when the point's refusal can be endorsed
     */
    public boolean isEndorsable() {
        return endorsable;
    }
}
