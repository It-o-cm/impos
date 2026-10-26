/* BO-03-02-45: the tender an expense is settled in, as the back office administered it. */
(function () {
    'use strict';

    /**
     * Points the tender selector at the tender of the expense just chosen.
     * <p>
     * An expense administered in a tender selects it; an expense administered in
     * none brings the selector back to cash (its empty value) — the tender of the
     * PREVIOUS expense must never stay behind. Choosing an expense also forgets that
     * the cashier picked a tender by hand: the server then books the expense in the
     * tender the back office gave it.
     *
     * @param {HTMLSelectElement} select the expense selector
     */
    function tenderOfExpense(select) {
        var option = select.options[select.selectedIndex];
        var tender = option && option.dataset.tender ? option.dataset.tender : '';
        var method = document.getElementById('paymentMethod');
        if (!method) {
            return;
        }
        method.value = tender;
        document.getElementById('tenderPicked').value = '';
    }

    /**
     * Records that the cashier chose the tender by hand, so the server books the
     * movement in that tender rather than in the expense's administered one.
     */
    function markPicked() {
        document.getElementById('tenderPicked').value = 'true';
    }

    /**
     * Wires the two selectors of the cash-movement screen, when it carries them.
     */
    function boot() {
        var reason = document.getElementById('reason');
        if (!reason) {
            return;
        }
        reason.addEventListener('change', function () { tenderOfExpense(reason); });
        var method = document.getElementById('paymentMethod');
        if (method) {
            method.addEventListener('change', markPicked);
        }
    }

    boot();
})();
