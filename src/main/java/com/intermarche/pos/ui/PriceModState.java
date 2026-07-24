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
     * Opens the modal on a target line.
     *
     * @param type the modification type
     * @param uid the uid of the targeted line
     * @param label the label of the targeted line
     */
    public void set(String type, String uid, String label) {
        this.active = true;
        this.type = type;
        this.targetUid = uid;
        this.targetLabel = label;
    }

    /**
     * Closes the modal and forgets the target.
     */
    public void clear() {
        this.active = false;
        this.type = null;
        this.targetUid = null;
        this.targetLabel = null;
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
        return "MODIFICATION";
    }
}
