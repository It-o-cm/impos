package com.intermarche.pos.ui.hardware;

/**
 * The cashier's end-of-transaction printing choice (LC-08-03-01 to
 * LC-08-03-06): WHICH of the three documents a closed sale can produce are
 * actually printed.
 * <p>
 * The choice is only ever offered when the back office activated conditional
 * printing ({@code print.conditional-enabled}); with the option off the
 * register prints exactly as it did before, and this enum is never consulted.
 * <p>
 * A choice is a REQUEST, not a decision: {@link PrintPolicy} combines it with
 * the administered forced-printing rules, which can add back a document the
 * cashier excluded — {@link #NONE} included. That separation is deliberate:
 * this enum stays a pure value, so it carries no back-office knowledge.
 */
public enum PrintChoice {

    /** Every document is printed (LC-08-03-02) — the standard behaviour. */
    ALL("Tous les tickets", "btn-success", true, true, true),

    /** Only the sale ticket is printed (LC-08-03-03). */
    SALE_TICKET("Ticket de caisse", "btn-muted", true, false, false),

    /** Only the card receipt is printed (LC-08-03-04). */
    CARD_RECEIPT("Ticket carte bancaire", "btn-muted", false, true, false),

    /** Only the purchase voucher is printed (LC-08-03-05). */
    VOUCHER("Bon d'achat", "btn-muted", false, false, true),

    /** Nothing is printed (LC-08-03-06), forced documents excepted. */
    NONE("Aucun ticket", "btn-danger", false, false, false);

    /** The operator-facing label of the choice button. */
    private final String label;

    /**
     * The theme colour modifier of the choice button. The three choices are
     * read at a glance because they do not look alike: the standard behaviour
     * is the green one, printing nothing is the red one, and the partial
     * choices stay neutral.
     */
    private final String buttonClass;

    /** Whether this choice asks for the sale ticket. */
    private final boolean saleTicket;

    /** Whether this choice asks for the card receipt. */
    private final boolean cardReceipt;

    /** Whether this choice asks for the purchase voucher. */
    private final boolean voucher;

    /**
     * Builds a choice with the documents it asks for.
     *
     * @param label the operator-facing label
     * @param buttonClass the theme colour modifier of the choice button
     * @param saleTicket whether the sale ticket is asked for
     * @param cardReceipt whether the card receipt is asked for
     * @param voucher whether the purchase voucher is asked for
     */
    PrintChoice(String label, String buttonClass, boolean saleTicket, boolean cardReceipt,
                boolean voucher) {
        this.label = label;
        this.buttonClass = buttonClass;
        this.saleTicket = saleTicket;
        this.cardReceipt = cardReceipt;
        this.voucher = voucher;
    }

    /**
     * Returns the operator-facing label of the choice.
     *
     * @return the label, in French
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the theme colour modifier of the choice button.
     *
     * @return the CSS class name, never blank
     */
    public String getButtonClass() {
        return buttonClass;
    }

    /**
     * Whether the choice asks for the sale ticket.
     *
     * @return true when the sale ticket is requested
     */
    public boolean isSaleTicket() {
        return saleTicket;
    }

    /**
     * Whether the choice asks for the card receipt.
     *
     * @return true when the card receipt is requested
     */
    public boolean isCardReceipt() {
        return cardReceipt;
    }

    /**
     * Whether the choice asks for the purchase voucher.
     *
     * @return true when the purchase voucher is requested
     */
    public boolean isVoucher() {
        return voucher;
    }

    /**
     * Parses a choice coming from the screen. An unknown, blank or missing
     * value resolves to {@link #ALL}: a lost form value must never silently
     * suppress a fiscal document.
     *
     * @param raw the raw form value, possibly null
     * @return the parsed choice, never null
     */
    public static PrintChoice of(String raw) {
        if (raw == null) {
            return ALL;
        }
        String trimmed = raw.trim();
        for (PrintChoice choice : values()) {
            if (choice.name().equalsIgnoreCase(trimmed)) {
                return choice;
            }
        }
        return ALL;
    }
}
