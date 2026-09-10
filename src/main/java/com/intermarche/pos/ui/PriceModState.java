package com.intermarche.pos.ui;

import java.io.Serializable;

/**
 * In-memory state of the line-modification modal (price modifications and,
 * since the quantity integration, the line-quantity edition — same target
 * resolution, same modal, same numpad).
 */
public class PriceModState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True while the modal is shown. */
    public boolean active = false;

    /** The modification type (REMISE, DISCOUNT, FORCE_PRICE, QUANTITY). */
    public String type = null;

    /** The uid of the targeted ticket line. */
    public String targetUid = null;

    /** The label of the targeted ticket line. */
    public String targetLabel = null;

    /**
     * The targeted line as the ticket itself displays it — quantity and label,
     * the very fragment {@code TicketItem.getHtml()} produces — or null for a
     * ticket-level gesture.
     *
     * <p>The modal used to name the article and nothing else. A remise, a
     * discount and a price forcing are all typed blind against a line the
     * operator selected one gesture earlier and can no longer see behind the
     * modal's black overlay: "PAIN DE MIE" says neither how many, nor at what
     * price, nor that a remise is already on it. The line is recalled, in the
     * same shape it has on the ticket, so the two readings cannot disagree.
     */
    public String targetHtml = null;

    /** The current total of the targeted line, formatted, or null. */
    public String targetPriceFormatted = null;

    /** The modification already applied to the targeted line, or null when there is none. */
    public String targetModifierLabel = null;

    /**
     * Opens the modal on a target that is not a ticket line — the ticket-level
     * remise and discount, which have no line to recall.
     *
     * @param type the modification type
     * @param uid the uid of the targeted line, null for a ticket-level gesture
     * @param label the label shown in place of the line recall
     */
    public void set(String type, String uid, String label) {
        set(type, uid, label, null, null, null);
    }

    /**
     * Opens the modal on a target line, carrying the recall of that line.
     *
     * @param type the modification type
     * @param uid the uid of the targeted line
     * @param label the label of the targeted line
     * @param html the line as the ticket displays it (quantity and label), or null
     * @param priceFormatted the current total of the line, formatted, or null
     * @param modifierLabel the modification already applied to the line, or null
     */
    public void set(String type, String uid, String label, String html,
            String priceFormatted, String modifierLabel) {
        this.active = true;
        this.type = type;
        this.targetUid = uid;
        this.targetLabel = label;
        this.targetHtml = html;
        this.targetPriceFormatted = priceFormatted;
        this.targetModifierLabel = modifierLabel;
    }

    /**
     * Closes the modal and forgets the target.
     */
    public void clear() {
        this.active = false;
        this.type = null;
        this.targetUid = null;
        this.targetLabel = null;
        this.targetHtml = null;
        this.targetPriceFormatted = null;
        this.targetModifierLabel = null;
    }

    /**
     * Indicates whether the modal has a ticket line to recall.
     *
     * @return true when the target is a line whose display fragment was captured
     */
    public boolean isTargetLine() {
        return targetHtml != null;
    }

    /**
     * Indicates whether the modal must offer the three ways of changing the
     * price of ONE line — a remise in euros, a discount in percent, a forced
     * price.
     *
     * <p>These three were three separate keys of the VENTE menu, a quarter of
     * the ten slots the register has, for what is one gesture in three modes:
     * the operator picks the line, then says how the price moves. The
     * ticket-level remise already made that choice inside the modal
     * ({@link #isTicketModes()}); the line-level one now does the same, and the
     * menu carries one key instead of three. QUANTITY shares the modal but not
     * the question — it changes how many, not how much — so it is not here.
     *
     * @return true for REMISE, DISCOUNT and FORCE_PRICE
     */
    public boolean isLineModes() {
        return "REMISE".equals(type) || "DISCOUNT".equals(type) || "FORCE_PRICE".equals(type);
    }

    /**
     * Indicates whether the modal must offer the two ways of discounting the
     * WHOLE ticket — an amount in euros or a percentage.
     *
     * @return true for GLOBAL_REMISE and GLOBAL_DISCOUNT
     */
    public boolean isTicketModes() {
        return "GLOBAL_REMISE".equals(type) || "GLOBAL_DISCOUNT".equals(type);
    }

    /**
     * Returns the modal title for the current type.
     *
     * @return the display title
     */
    public String getTypeLabel() {
        if ("REMISE".equals(type)) return "SAISIE REMISE (€)";
        if ("DISCOUNT".equals(type)) return "SAISIE DISCOUNT (%)";
        if ("FORCE_PRICE".equals(type)) return "NOUVEAU PRIX (€)";
        if ("QUANTITY".equals(type)) return "QUANTITÉ ARTICLE";
        if ("GLOBAL_REMISE".equals(type)) return "REMISE TICKET (€)";
        if ("GLOBAL_DISCOUNT".equals(type)) return "REMISE TICKET (%)";
        return "MODIFICATION";
    }
}
