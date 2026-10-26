package com.intermarche.pos.ui.recall;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The control of a selfscanning basket recalled at the till (LC-06-05-03 to -06),
 * held by the register state while the cashier controls it.
 *
 * <p>The basket is what the customer declared on the device; the control is what
 * the cashier re-scans. A PARTIAL control asks for a few articles of the basket
 * (LC-06-05-04); a TOTAL control asks for every one of them, and the sale is then
 * built from what was re-scanned (LC-06-05-05). The cashier may provoke a total
 * control at any time (LC-06-05-06), and a partial control that meets an article
 * the basket does not declare becomes total on its own.
 */
public class SelfScanControl {

    /** The basket as the selfscanning device declared it. */
    public final RecalledDocument basket;

    /** The control in progress: NONE, PARTIAL or TOTAL. */
    public String mode;

    /** Whether the partial control was drawn by the store's rate rather than asked by the device. */
    public boolean drawn;

    /** Whether the cashier provoked the total control (LC-06-05-06). */
    public boolean provoked;

    /** The number of articles a partial control asks for. */
    public final int partialTarget;

    /** The articles a partial control has confirmed so far. */
    public int partialChecked;

    /** The quantity the basket declares, per EAN, in the basket's order. */
    public final Map<String, BigDecimal> declared = new LinkedHashMap<>();

    /** The quantity the cashier re-scanned, per EAN, in the order of the scans. */
    public final Map<String, BigDecimal> controlled = new LinkedHashMap<>();

    /** The label of every EAN met, declared or re-scanned. */
    public final Map<String, String> labels = new LinkedHashMap<>();

    /** The last message of the control, or null. */
    public String message;

    /** Whether the last message reports a success. */
    public boolean messageOk;

    /**
     * Opens the control of a basket.
     *
     * @param basket the basket the device declared
     * @param mode the control to run
     * @param partialTarget the number of articles a partial control asks for
     */
    public SelfScanControl(RecalledDocument basket, String mode, int partialTarget) {
        this.basket = basket;
        this.mode = mode;
        this.partialTarget = partialTarget;
        for (RecalledDocument.Line line : basket.lines) {
            if (line == null || line.ean() == null || line.ean().isBlank()) {
                continue;
            }
            declared.merge(line.ean(), line.soldQuantity(), BigDecimal::add);
            labels.putIfAbsent(line.ean(), line.label());
        }
    }

    /**
     * Whether a partial control is running.
     *
     * @return true in a partial control
     */
    public boolean isPartial() {
        return RecalledDocument.CONTROL_PARTIAL.equals(mode);
    }

    /**
     * Whether a total control is running.
     *
     * @return true in a total control
     */
    public boolean isTotal() {
        return RecalledDocument.CONTROL_TOTAL.equals(mode);
    }

    /**
     * Whether the partial control has confirmed the articles it asks for.
     *
     * @return true once the partial target is reached
     */
    public boolean isPartialPassed() {
        return partialChecked >= partialTarget;
    }

    /**
     * Whether the basket may be loaded into the sale: no control, a partial
     * control passed, or a total control (whose re-scan is the sale).
     *
     * @return true when the basket may be loaded
     */
    public boolean isLoadable() {
        return !isPartial() || isPartialPassed();
    }

    /**
     * The quantity declared for an EAN.
     *
     * @param ean the article EAN
     * @return the declared quantity, zero when the basket does not declare it
     */
    public BigDecimal declaredOf(String ean) {
        return declared.getOrDefault(ean, BigDecimal.ZERO);
    }

    /**
     * The quantity re-scanned for an EAN.
     *
     * @param ean the article EAN
     * @return the re-scanned quantity, zero when none
     */
    public BigDecimal controlledOf(String ean) {
        return controlled.getOrDefault(ean, BigDecimal.ZERO);
    }

    /**
     * Counts one more unit of an article re-scanned.
     *
     * @param ean the article EAN
     * @param label the article label, kept when the EAN was not met yet
     */
    public void count(String ean, String label) {
        controlled.merge(ean, BigDecimal.ONE, BigDecimal::add);
        labels.putIfAbsent(ean, label);
    }

    /**
     * The number of articles whose re-scanned quantity differs from the declared
     * one, in a total control (LC-06-05-05).
     *
     * @return the number of discrepancies, zero outside a total control
     */
    public int discrepancies() {
        if (!isTotal()) {
            return 0;
        }
        int count = 0;
        for (String ean : labels.keySet()) {
            if (declaredOf(ean).compareTo(controlledOf(ean)) != 0) {
                count++;
            }
        }
        return count;
    }
}
