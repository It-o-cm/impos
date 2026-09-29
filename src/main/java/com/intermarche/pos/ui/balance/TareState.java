package com.intermarche.pos.ui.balance;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * In-memory state of the PUNCTUAL container tare (LC-14-01-04/05): the tare of
 * the container the cashier selected or scanned for the NEXT weighing only.
 * <p>
 * It is deliberately NOT persistent: a container tare has priority over the
 * article tare (LC-14-01-07) and the default tare (LC-14-01-03) for one
 * weighing, then it is consumed — the cashier re-selects it at each weighing,
 * exactly as the questionnaire requires. The weigh flow calls {@link #clear()}
 * right after every weighing so a forgotten container can never silently tare
 * the next article.
 */
public class TareState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The punctual container tare in kilograms, or null when none is armed. */
    public BigDecimal containerTare = null;

    /** The label of the armed container, or null when none is armed. */
    public String containerLabel = null;

    /**
     * Indicates whether a punctual container tare is armed for the next weigh.
     *
     * @return true when a strictly positive container tare is armed
     */
    public boolean hasContainer() {
        return containerTare != null && containerTare.signum() > 0;
    }

    /**
     * Consumes the punctual container tare so it applies to a single weighing
     * only (LC-14-01-04: "la sélection est à renouveler à chaque pesée").
     */
    public void clear() {
        containerTare = null;
        containerLabel = null;
    }
}
