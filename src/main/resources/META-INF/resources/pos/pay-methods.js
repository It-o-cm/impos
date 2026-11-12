/* Règlement: the payment keys shown six at a time, the main ones first (pay screen). */
(function () {
    'use strict';

    /** How many payment keys one page of the column shows. */
    var PAGE_SIZE = 6;

    /**
     * Counts the pages a column of keys needs.
     * <p>
     * A column that holds one key more than a page shows them all: the AUTRE key
     * would take the very slot it frees.
     *
     * @param {number} total the number of payment keys
     * @returns {number} the page count, at least one
     */
    function pageCount(total) {
        if (total <= PAGE_SIZE + 1) {
            return 1;
        }
        return Math.ceil(total / PAGE_SIZE);
    }

    /**
     * Lists the payment keys of the column, in their order, without the navigation row.
     *
     * @param {HTMLElement} column the payment column
     * @returns {Element[]} the keys
     */
    function keysOf(column) {
        return Array.prototype.filter.call(column.children, function (child) {
            return !child.classList.contains('pm-nav');
        });
    }

    /**
     * Shows one page of keys and hides the others: the first page holds the main
     * methods, AUTRE replaces it by the following ones, PRINCIPAUX brings the
     * first page back.
     *
     * @param {HTMLElement} column the payment column
     * @param {number} page the zero-based page wanted, bounded to the pages that exist
     * @returns {number} the page shown
     */
    function show(column, page) {
        var keys = keysOf(column);
        var pages = pageCount(keys.length);
        var current = Math.max(0, Math.min(page, pages - 1));
        keys.forEach(function (key, index) {
            key.classList.toggle('pm-off', pages > 1 && Math.floor(index / PAGE_SIZE) !== current);
        });
        var nav = column.querySelector('.pm-nav');
        nav.classList.toggle('pm-off', pages === 1);
        nav.querySelector('[data-pm="main"]').classList.toggle('pm-off', current === 0);
        nav.querySelector('[data-pm="more"]').classList.toggle('pm-off', current === pages - 1);
        column.dataset.page = String(current);
        return current;
    }

    /**
     * Wires the two navigation keys of the payment column, when the screen carries it.
     */
    function boot() {
        var column = document.getElementById('payMethods');
        if (!column) {
            return;
        }
        var nav = column.querySelector('.pm-nav');
        nav.querySelector('[data-pm="more"]').addEventListener('click', function () {
            show(column, Number(column.dataset.page) + 1);
        });
        nav.querySelector('[data-pm="main"]').addEventListener('click', function () {
            show(column, 0);
        });
        show(column, 0);
    }

    boot();
})();
