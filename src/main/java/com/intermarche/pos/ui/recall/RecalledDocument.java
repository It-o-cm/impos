package com.intermarche.pos.ui.recall;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A document recalled at the till by a code the back office gave the customer
 * (EXP-90/91/92): a quote, an order, a sale voucher or a click-and-collect
 * basket. It is EMITTED and STORED by the store node (imedge); the register
 * only fetches it by code through {@link BackOfficeDocumentClient} and loads
 * its lines into the current sale.
 *
 * <p>All four kinds share one shape — a typed header, a list of article lines
 * and, for an order, the down-payment already collected — because the campaign
 * builds them on the SAME recall-by-code mechanism; only the {@link Kind} and
 * the label differ.
 *
 * <p>Since LC-06-03 the register CREATES orders too: the shape above doubles as
 * the push payload of a till-created order ({@code BackOfficeDocumentClient#pushOrder}),
 * the node storing it and any register of the store recalling it by its code.
 */
public class RecalledDocument {

    /** The kind of recalled document. */
    public enum Kind {
        /** A quote (devis): articles proposed, not yet ordered. */
        DEVIS,
        /** An order (commande): may carry a down-payment already paid. */
        COMMANDE,
        /** A sale voucher (bon de vente). */
        BON_VENTE,
        /** A click-and-collect prepared basket. */
        CLICK_COLLECT,
        /**
         * A basket the customer scanned himself on an in-store selfscanning
         * device (LC-06-05-03), controlled at the till before it is paid.
         */
        SELF_SCAN,
        /**
         * A basket built in the queue on a queue-busting handheld, loaded and
         * paid in full at the till that recalls it (BO-03-06-57).
         */
        QBUSTING,
        /**
         * A basket of the e-commerce platform taken back at the till
         * (BO-10-01-06, -07, -13), under the rules of the e-commerce channel.
         */
        ECOMMERCE,
        /**
         * A transaction finalised on a mobility device, possibly already paid
         * there, integrated at the till (BO-10-01-34 to -39).
         */
        MOBILITY
    }

    /**
     * A settlement a mobility device already collected on its transaction
     * (BO-10-01-39), restated at the till when the transaction is integrated.
     *
     * @param tender the settlement key the device used (CASH, CARD...), or null
     * @param amount the amount paid, TTC
     */
    public record Payment(String tender, BigDecimal amount) {
    }

    /** No control of a selfscanning basket (LC-06-05-04, -05). */
    public static final String CONTROL_NONE = "NONE";

    /** A partial control: a few articles of the basket are re-scanned (LC-06-05-04). */
    public static final String CONTROL_PARTIAL = "PARTIAL";

    /** A total control: every article of the basket is re-scanned (LC-06-05-05). */
    public static final String CONTROL_TOTAL = "TOTAL";

    /**
     * A reduction voucher a selfscanning device recorded on its basket, applied
     * automatically at payment (LC-06-05-02).
     *
     * @param code the voucher's code, or null
     * @param label the voucher's label
     * @param amount the voucher's amount, TTC
     */
    public record Voucher(String code, String label, BigDecimal amount) {
    }

    /** The deposit rule of a document that admits none (LC-06-02-12). */
    public static final String DEPOSIT_NONE = "NONE";

    /** The deposit rule of a document the customer may leave a deposit on (LC-06-02-04). */
    public static final String DEPOSIT_OPTIONAL = "OPTIONAL";

    /** The deposit rule of a document that asks for a deposit before it is settled (LC-06-02-04). */
    public static final String DEPOSIT_MANDATORY = "MANDATORY";

    /**
     * One article line of a recalled document.
     *
     * <p>A document from the commercial management tool may state two quantities
     * (LC-06-02-02, -12): the THEORETICAL one ordered, {@code quantity}, and the one
     * actually DELIVERED, {@code deliveredQuantity}; the delivered quantity is the
     * one sold, and both are shown.
     *
     * @param ean the article EAN (or internal code)
     * @param label the article label
     * @param quantity the quantity to load, the theoretical one when a delivered one is stated
     * @param unitPrice the unit price to load
     * @param vatRate the VAT rate of the line
     * @param deliveredQuantity the quantity delivered, or null when the document states none
     */
    public record Line(String ean, String label, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal vatRate, BigDecimal deliveredQuantity) {

        /**
         * Creates a line that states a single quantity.
         *
         * @param ean the article EAN (or internal code)
         * @param label the article label
         * @param quantity the quantity to load
         * @param unitPrice the unit price to load
         * @param vatRate the VAT rate of the line
         */
        public Line(String ean, String label, BigDecimal quantity, BigDecimal unitPrice, BigDecimal vatRate) {
            this(ean, label, quantity, unitPrice, vatRate, null);
        }

        /**
         * The quantity the sale takes: the delivered one when the document states
         * it, the ordered one otherwise.
         *
         * @return the quantity sold, one when the document states none
         */
        public BigDecimal soldQuantity() {
            if (deliveredQuantity != null) {
                return deliveredQuantity;
            }
            return quantity == null ? BigDecimal.ONE : quantity;
        }

        /**
         * The line total at its unit price for the quantity sold.
         *
         * @return the total, two decimals
         */
        public BigDecimal total() {
            BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            return price.multiply(soldQuantity()).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    /** The kind of document. */
    public Kind kind;

    /** The recall code the customer presented. */
    public String code;

    /** The article lines to load into the sale. */
    public List<Line> lines = new ArrayList<>();

    /**
     * The down-payment already collected on an order (EXP-85), deducted from the
     * amount due when the order is recalled; ZERO when none.
     */
    public BigDecimal acompteAlreadyPaid = BigDecimal.ZERO;

    /**
     * The per-rate VAT of the down-payment already collected, in the compact
     * {@code DepositVat} form ({@code rate:amount;...}), or null when the
     * node carried none (LC-06-03-05/-08). The register computes it when the
     * order is CREATED at the till and the node hands it back at recall, so
     * the final ticket restates the ventilation printed on the deposit slip.
     */
    public String depositVatDetail;

    /**
     * The total amount of the order (TTC), or null when the node did not
     * state one. Set by the till when the order is created there
     * (LC-06-03-01) and pushed to the node with the order.
     */
    public BigDecimal totalAmount;

    /**
     * The document's own label, as the commercial management tool named it, or
     * null.
     */
    public String label;

    /**
     * The date the order was placed, ISO-8601 as the store node states it, or
     * null when the node states none (BO-10-03-17) — sent on to the promotion
     * engine where the store administers it. A sale voucher is valid on the
     * day it was issued only (BO-10-01-40).
     */
    public String orderDate;

    /**
     * Whether the document admits a deposit at the till (LC-06-02-04): one of
     * {@link #DEPOSIT_NONE}, {@link #DEPOSIT_OPTIONAL}, {@link #DEPOSIT_MANDATORY};
     * null reads as none, the rule of every document the till created itself.
     */
    public String depositRule;

    /**
     * The control the selfscanning device asks for on its basket (LC-06-05-04,
     * -05): one of {@link #CONTROL_NONE}, {@link #CONTROL_PARTIAL},
     * {@link #CONTROL_TOTAL}; null reads as none.
     */
    public String control;

    /**
     * The reduction vouchers the selfscanning device recorded on its basket
     * (LC-06-05-02), never null.
     */
    public List<Voucher> vouchers = new ArrayList<>();

    /**
     * The customer number the document was issued for, or null (BO-10-01-18).
     */
    public String customerNumber;

    /**
     * The settlements a mobility device already collected on its transaction
     * (BO-10-01-39), never null.
     */
    public List<Payment> payments = new ArrayList<>();

    /**
     * Whether the cashier KEYED the code on the recall screen rather than
     * scanning it (BO-10-01-16, -25, -30, -40): the retrieval mode of the
     * channel is judged on it. Register-side only, never on the wire.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean keyed;

    /**
     * Whether the document was found by the CUSTOMER NUMBER rather than by its
     * own code (BO-10-01-18). Register-side only, never on the wire.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean byCustomer;

    /**
     * How many lines the channel rules carried on the generic EAN of the
     * unknown articles (BO-10-01-06, -21). Register-side only.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int unknownMarked;

    /**
     * How many coupons of the basket the channel rules did not take back
     * (BO-10-01-20). Register-side only.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int couponsDropped;

    /**
     * Creates an empty document (fields set by the client after a fetch).
     */
    public RecalledDocument() {
    }

    /**
     * Creates a recalled document.
     *
     * @param kind the document kind
     * @param code the recall code
     * @param lines the article lines
     * @param acompteAlreadyPaid the down-payment already paid, or null for none
     */
    public RecalledDocument(Kind kind, String code, List<Line> lines, BigDecimal acompteAlreadyPaid) {
        this.kind = kind;
        this.code = code;
        this.lines = lines != null ? lines : new ArrayList<>();
        this.acompteAlreadyPaid = acompteAlreadyPaid != null ? acompteAlreadyPaid : BigDecimal.ZERO;
    }

    /**
     * The operator-facing label of the document kind.
     *
     * @return the French label of the kind, or "DOCUMENT" when the kind is unset
     */
    public String getKindLabel() {
        if (kind == null) {
            return "DOCUMENT";
        }
        return switch (kind) {
            case DEVIS -> "DEVIS";
            case COMMANDE -> "COMMANDE";
            case BON_VENTE -> "BON DE VENTE";
            case CLICK_COLLECT -> "CLICK AND COLLECT";
            case SELF_SCAN -> "SELFSCANNING";
            case QBUSTING -> "QBUSTING";
            case ECOMMERCE -> "E-COMMERCE";
            case MOBILITY -> "MOBILITÉ";
        };
    }

    /**
     * The bundle key of the operator-facing label of the document kind, so the
     * screens and the messages name the kind in the register's language.
     *
     * @return {@code recall.kind.} followed by the kind name, or by DOCUMENT
     *         when the kind is unset
     */
    public String getKindKey() {
        return "recall.kind." + (kind == null ? "DOCUMENT" : kind.name());
    }

    /**
     * Whether the till may take a deposit on this document (LC-06-02-04).
     *
     * @return true when the document's rule is optional or mandatory
     */
    public boolean offersDeposit() {
        return DEPOSIT_OPTIONAL.equals(depositRule) || DEPOSIT_MANDATORY.equals(depositRule);
    }

    /**
     * Whether the document still asks for a deposit before it can be settled
     * (LC-06-02-04): its rule is mandatory and nothing has been paid on it yet.
     *
     * @return true when a deposit must be taken first
     */
    public boolean depositRequired() {
        return DEPOSIT_MANDATORY.equals(depositRule) && !hasAcompte();
    }

    /**
     * Whether this document carries a down-payment to deduct (EXP-85).
     *
     * @return true when the paid down-payment is strictly positive
     */
    public boolean hasAcompte() {
        return acompteAlreadyPaid != null && acompteAlreadyPaid.signum() > 0;
    }
}
