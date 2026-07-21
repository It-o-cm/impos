package com.intermarche.pos.ui.payment;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PaymentState implements Serializable {
    private static final long serialVersionUID = 1L;

    // Nombre de lignes de paiement affichables sans ascenseur (liste pleine).
    private static final int PAGE_SIZE = 5;

    public List<PaymentEntry> payments = new ArrayList<>();
    public double paidAmount = 0.0;
    public boolean transactionComplete = false;
    public Long ticketDbId = null;

    // Pagination de l'historique des paiements (précédent / suivant)
    public int currentPage = 0;

    // Champs pour l'affichage UI
    public Double lastChangeAmount = null; // Rendu monnaie
    public String inputMode = null;
    public String temporaryInput = "0,00";

    // Saisie d'un bon en cours (mode manuel, ou Catalina reconnu au scan)
    public boolean voucherPanelOpen = false;
    public String pendingVoucherTypeCode = null;
    public String pendingVoucherLabel = null;
    public String pendingVoucherNumber = null;
    public boolean pendingVoucherNeedsAmount = false;
    public String voucherError = null;

    public void addPayment(String method, double amount) {
        payments.add(new PaymentEntry(method, amount));
        paidAmount += amount;
        goToLastPage();
        clearTemporaryInputs();
    }

    public void addCashPayment(double amount, double tenderedAmount) {
        payments.add(new PaymentEntry("CASH", amount, tenderedAmount));
        paidAmount += amount;
        // IMPORTANT : On ne nettoie PAS lastChangeAmount ici, car on en a besoin pour la modale de fin
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
    public void addVoucherPayment(String label, String number, double amount) {
        payments.add(new PaymentEntry(label, amount, number, true));
        paidAmount += amount;
        goToLastPage();
        clearTemporaryInputs();
    }

    public void clearPayments() {
        payments.clear();
        paidAmount = 0.0;
        currentPage = 0;
        clearTemporaryInputs();
    }

    public void reset() {
        clearPayments();
        transactionComplete = false;
        ticketDbId = null;
        lastChangeAmount = null; // On le nettoie seulement ici (nouvelle transaction complète)
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

    private void clearTemporaryInputs() {
        this.inputMode = null;
        this.temporaryInput = "0,00";
    }

    // --------------------------------------------------
    // Pagination de l'historique des paiements
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

    // Classe interne PaymentEntry
    public static class PaymentEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public String method;
        public double amount;
        public Double tenderedAmount;
        public String voucherNumber;
        public boolean voucher;

        public PaymentEntry(String method, double amount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
        }

        public PaymentEntry(String method, double amount, double tenderedAmount) {
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
        public PaymentEntry(String method, double amount, String voucherNumber, boolean voucher) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.voucherNumber = voucherNumber;
            this.voucher = voucher;
        }

        public String getFormattedAmount() {
            return String.format("%.2f", amount).replace(".", ",");
        }

        public String getFormattedTendered() {
            if (tenderedAmount == null) return "-";
            return String.format("%.2f", tenderedAmount).replace(".", ",");
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
