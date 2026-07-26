package com.intermarche.pos.ui.cash;

/**
 * A cash denomination shown on the drawer-count page: banknote, coin or
 * coin roll.
 * <p>
 * Display-only model: the {@code double} values feed the on-screen counting
 * calculator, never a fiscal amount — the counted total the cashier
 * validates travels as a string and is parsed as {@code BigDecimal} by the
 * Z-closing flow, and the per-denomination detail is persisted as the
 * session's {@code countDetail} JSON.
 */
public class CashItem {
    /** The stable identifier of the denomination (also the JSON key of the count detail). */
    public String id;
    /** The label shown on the counting page. */
    public String label;
    /** The unit value in euros (roll entries carry the whole roll's value). */
    public double value;

    /**
     * Creates a denomination entry.
     *
     * @param id the stable identifier
     * @param label the display label
     * @param value the unit value in euros
     */
    public CashItem(String id, String label, double value) {
        this.id = id;
        this.label = label;
        this.value = value;
    }
}
