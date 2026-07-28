package com.intermarche.pos.ui.payment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory state of the payment in progress for the current ticket.
 * <p>
 * All monetary amounts are {@link BigDecimal} (phase 0).
 * <p>
 * {@code paymentInProgress} is THE payment-context discriminator of the
 * whole register — the voucher scan handler routes on it, parking refuses
 * on it, the training toggle refuses on it. It exists precisely because
 * {@code ticketDbId} stopped meaning "payment started" the day the draft
 * became early (first article): the id now lives for the whole sale, the
 * flag marks the payment phase alone. Payment entries keep their 1-based
 * registration order; restart recovery and the completion modal rebuild
 * from them, and {@code transactionComplete} + {@code lastChangeAmount}
 * are what the completion modal and the customer thank-you screen read —
 * both survive a restart through the recovery path.
 */
public class PaymentState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Number of payment lines displayable without a scrollbar (full list). */
    private static final int PAGE_SIZE = 5;

    /** The payments registered so far. */
    public List<PaymentEntry> payments = new ArrayList<>();

    /** The sum of registered payment amounts. */
    public BigDecimal paidAmount = BigDecimal.ZERO;

    /** True once the remaining due reaches zero. */
    public boolean transactionComplete = false;

    /** The database id of the draft ticket, or null before creation. */
    public Long ticketDbId = null;

    /**
     * Amount awaiting the virtual payment terminal's decision, or null when
     * no card request is pending (phase 6: the simulator accepts or refuses).
     */
    public BigDecimal pendingCardAmount = null;

    /**
     * Outcome of the remote valuation at payment entry (phase 7): LOCAL
     * (engine not configured), ENGINE (valued), DEGRADED (engine failed,
     * catalog prices apply). Null before payment entry.
     */
    public String valuationStatus = null;

    /**
     * Raw JSON of the engine's valuation when {@code valuationStatus} is
     * ENGINE — held for the lot 2 reconciliation, null otherwise.
     */
    public String valuationJson = null;

    /**
     * The engine's own total including tax, kept for log comparison against
     * the register's authoritative total. Null unless valued.
     */
    public BigDecimal valuationEngineTotal = null;

    /**
     * Total adjustment applied by the reconciliation (valued minus local
     * over the covered lines, advantages allocated), tax included. Negative
     * when the engine grants advantages; null before payment entry, zero
     * when valued without effect.
     */
    public BigDecimal valuationAdjustment = null;

    /**
     * Remaining meal-voucher eligible base (tax included) from the engine's
     * MEAL_VOUCHER advantage, decremented by each registered meal-ticket
     * payment. Null when the engine emitted none (no cap applies).
     */
    public BigDecimal valuationMealEligible = null;

    /**
     * Meal-voucher threshold (legal cap per payment context) from the
     * engine, or null.
     */
    public BigDecimal valuationMealThreshold = null;

    /**
     * Upsell suggestions of the engine, ready to display (product labels
     * resolved). Never null, empty when none.
     */
    public java.util.ArrayList<String> valuationUpsells = new java.util.ArrayList<>();

    /**
     * True while the payment screen drives the transaction. Needed since the
     * early draft (phase 0 lot 2): {@link #ticketDbId} is set from the first
     * article, so it no longer indicates an active payment.
     */
    public boolean paymentInProgress = false;

    /** Current page of the payment history (previous / next). */
    public int currentPage = 0;

    /** Change given back on the last cash payment, or null. Kept for the end-of-transaction modal. */
    public BigDecimal lastChangeAmount = null;

    /** Current input mode of the payment numpad, or null. */
    public String inputMode = null;

    /** Current content of the payment numpad display. */
    public String temporaryInput = "0,00";

    /** True while the voucher panel is open (manual entry, or Catalina recognized at scan). */
    public boolean voucherPanelOpen = false;

    /** The technical code of the selected coupon type, or null. */
    public String pendingVoucherTypeCode = null;

    /** The display label of the selected coupon type, or null. */
    public String pendingVoucherLabel = null;

    /** The voucher number being entered, or null. */
    public String pendingVoucherNumber = null;

    /** True when the cashier must type the voucher amount. */
    public boolean pendingVoucherNeedsAmount = false;

    /** The current voucher entry error, or null. */
    public String voucherError = null;

    /**
     * Registers a plain payment (card, cheque, meal ticket, fidelity...).
     *
     * @param method the payment method key
     * @param amount the paid amount
     */
    public void addPayment(String method, BigDecimal amount) {
        payments.add(new PaymentEntry(method, amount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a cash payment with the tendered amount.
     * <p>
     * Important: {@link #lastChangeAmount} is NOT cleared here, it is needed
     * by the end-of-transaction modal.
     *
     * @param amount the amount applied to the ticket
     * @param tenderedAmount the amount handed over by the customer
     */
    public void addCashPayment(BigDecimal amount, BigDecimal tenderedAmount) {
        payments.add(new PaymentEntry("CASH", amount, tenderedAmount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Adds a voucher payment carrying its type label and optional number.
     *
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void addVoucherPayment(String label, String number, BigDecimal amount) {
        payments.add(new PaymentEntry(label, amount, number, true));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Clears the registered payments and resets the history pagination.
     */
    public void clearPayments() {
        payments.clear();
        paidAmount = BigDecimal.ZERO;
        currentPage = 0;
        clearTemporaryInputs();
    }

    /**
     * Resets the whole payment state for a new transaction.
     */
    public void reset() {
        clearPayments();
        transactionComplete = false;
        ticketDbId = null;
        paymentInProgress = false;
        pendingCardAmount = null;
        valuationStatus = null;
        valuationJson = null;
        valuationEngineTotal = null;
        valuationAdjustment = null;
        valuationMealEligible = null;
        valuationMealThreshold = null;
        valuationUpsells = new java.util.ArrayList<>();
        lastChangeAmount = null; // Cleared only here (full new transaction)
        clearPendingVoucher();
    }

    /**
     * Clears any in-progress voucher entry and its error message.
     */
    public void clearPendingVoucher() {
        voucherPanelOpen = false;
        pendingVoucherTypeCode = null;
        pendingVoucherLabel = null;
        pendingVoucherNumber = null;
        pendingVoucherNeedsAmount = false;
        voucherError = null;
    }

    /**
     * Resets the payment numpad input mode and display.
     */
    private void clearTemporaryInputs() {
        this.inputMode = null;
        this.temporaryInput = "0,00";
    }

    // --------------------------------------------------
    // Payment history pagination
    // --------------------------------------------------

    /**
     * Returns the total number of pages of payments (at least one).
     *
     * @return the page count
     */
    public int getTotalPages() {
        if (payments.isEmpty()) return 1;
        return (int) Math.ceil((double) payments.size() / PAGE_SIZE);
    }

    /**
     * Moves the view to the last page so the most recent payments are shown.
     */
    public void goToLastPage() {
        currentPage = getTotalPages() - 1;
    }

    /**
     * Returns the payments visible on the current page.
     *
     * @return the sublist of payments for the current page
     */
    public List<PaymentEntry> getVisiblePayments() {
        if (payments.isEmpty()) return Collections.emptyList();
        int maxPage = getTotalPages() - 1;
        if (currentPage > maxPage) currentPage = maxPage;
        if (currentPage < 0) currentPage = 0;
        int fromIndex = currentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, payments.size());
        if (fromIndex >= payments.size()) return Collections.emptyList();
        return payments.subList(fromIndex, toIndex);
    }

    /**
     * Indicates whether a previous page of payments exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasPreviousPage() {
        return currentPage > 0;
    }

    /**
     * Indicates whether a next page of payments exists.
     *
     * @return true if more payments follow the current page
     */
    public boolean isHasNextPage() {
        return (currentPage + 1) * PAGE_SIZE < payments.size();
    }

    /**
     * Indicates whether pagination controls are needed (more than one page).
     *
     * @return true if the payments span more than one page
     */
    public boolean isPaginated() {
        return payments.size() > PAGE_SIZE;
    }

    /**
     * Returns the 1-based current page number for display.
     *
     * @return the current page number
     */
    public int getCurrentPageDisplay() {
        return currentPage + 1;
    }

    /**
     * Indicates whether the payment at the given index belongs to the current page.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is on the current page
     */
    public boolean isOnCurrentPage(int index) {
        int fromIndex = currentPage * PAGE_SIZE;
        int toIndex = fromIndex + PAGE_SIZE;
        return index >= fromIndex && index < toIndex;
    }

    /**
     * Indicates whether the payment at the given index is among the two most recent.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is one of the last two
     */
    public boolean isAmongLastTwo(int index) {
        return index >= payments.size() - 2;
    }

    /**
     * Moves to the next page of payments if one exists.
     */
    public void nextPage() {
        if (isHasNextPage()) currentPage++;
    }

    /**
     * Moves to the previous page of payments if one exists.
     */
    public void prevPage() {
        if (isHasPreviousPage()) currentPage--;
    }

    /**
     * A single registered payment.
     */
    public static class PaymentEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        /** The payment method key, or the voucher type label for voucher entries. */
        public String method;

        /** The amount applied to the ticket. */
        public BigDecimal amount;

        /** The tendered amount for cash payments, or null. */
        public BigDecimal tenderedAmount;

        /** The voucher number, or null. */
        public String voucherNumber;

        /** True when this entry is a voucher payment. */
        public boolean voucher;

        /**
         * Creates a plain payment entry.
         *
         * @param method the payment method key
         * @param amount the paid amount
         */
        public PaymentEntry(String method, BigDecimal amount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
        }

        /**
         * Creates a cash payment entry with the tendered amount.
         *
         * @param method the payment method key (CASH)
         * @param amount the amount applied to the ticket
         * @param tenderedAmount the amount handed over by the customer
         */
        public PaymentEntry(String method, BigDecimal amount, BigDecimal tenderedAmount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = tenderedAmount;
        }

        /**
         * Creates a voucher payment entry holding its display label and number.
         *
         * @param method the display label of the voucher type
         * @param amount the paid amount
         * @param voucherNumber the voucher number, or null when there is none
         * @param voucher always true; marks the entry as a voucher payment
         */
        public PaymentEntry(String method, BigDecimal amount, String voucherNumber, boolean voucher) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.voucherNumber = voucherNumber;
            this.voucher = voucher;
        }

        /**
         * Returns the paid amount formatted for display (2 decimals, French comma).
         *
         * @return the formatted amount
         */
        public String getFormattedAmount() {
            return String.format("%.2f", amount.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
        }

        /**
         * Returns the tendered amount formatted for display, or "-" when absent.
         *
         * @return the formatted tendered amount
         */
        public String getFormattedTendered() {
            if (tenderedAmount == null) return "-";
            return String.format("%.2f", tenderedAmount.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
        }

        /**
         * Indicates whether this entry is a voucher payment.
         *
         * @return true if the entry was created as a voucher payment
         */
        public boolean isVoucher() {
            return voucher;
        }

        /**
         * Returns the voucher number for display, or an empty string when there is none.
         *
         * @return the voucher number, or an empty string
         */
        public String getVoucherNumber() {
            return voucherNumber != null ? voucherNumber : "";
        }
    }
}
