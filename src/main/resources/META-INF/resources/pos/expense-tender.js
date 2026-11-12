/* BO-03-02-45: the tender an expense is settled in, as the back office administered it. */
(function () {
    'use strict';

    /**
     * Points the tender at the tender of the expense just chosen.
     * <p>
     * The two choices are rows of tiles, each writing the field the form posts
     * (#reason, #paymentMethod). An expense administered in a tender selects it;
     * an expense administered in none brings the tender back to cash (its empty
     * value) — the tender of the PREVIOUS expense must never stay behind.
     * Choosing an expense also forgets that the cashier picked a tender by hand:
     * the server then books the expense in the tender the back office gave it.
     *
     * @param {HTMLElement} tile the expense tile chosen, or null for none
     */
    function tenderOfExpense(tile) {
        var tender = tile && tile.dataset.tender ? tile.dataset.tender : '';
        var method = document.getElementById('paymentMethod');
        if (!method) {
            return;
        }
        method.value = tender;
        document.querySelectorAll('input[name=tenderTile]').forEach(function (choice) {
            choice.checked = choice.value === tender;
        });
        document.getElementById('tenderPicked').value = '';
    }

    /**
     * Records the tender the cashier chose by hand, so the server books the
     * movement in that tender rather than in the expense's administered one.
     *
     * @param {HTMLInputElement} tile the tender tile chosen
     */
    function markPicked(tile) {
        document.getElementById('paymentMethod').value = tile.value;
        document.getElementById('tenderPicked').value = 'true';
    }

    /**
     * Records the expense the cashier chose and brings its tender along.
     *
     * @param {HTMLInputElement} tile the expense tile chosen
     */
    function chooseExpense(tile) {
        document.getElementById('reason').value = tile.value;
        tenderOfExpense(tile);
    }

    /**
     * Wires the tiles of the cash-movement screen, when it carries them.
     */
    function boot() {
        if (!document.getElementById('reason')) {
            return;
        }
        document.querySelectorAll('input[name=reasonTile]').forEach(function (tile) {
            tile.addEventListener('change', function () { chooseExpense(tile); });
        });
        document.querySelectorAll('input[name=tenderTile]').forEach(function (tile) {
            tile.addEventListener('change', function () { markPicked(tile); });
        });
    }

    boot();
})();
