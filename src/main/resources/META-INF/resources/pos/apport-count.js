/* LC-12-04-02: the float brought into the drawer, counted denomination by denomination. */
(function () {
    'use strict';
    var countState = {};
    var countValues = {};
    var selectedElement = null;

    /**
     * The cents of a denomination value, so the running total is exact: a sum
     * of floating-point euros drifts, and a drifted 12,349999 would post 12,34.
     *
     * @param {number} value the denomination value in euros
     * @returns {number} the value in whole cents
     */
    function cents(value) {
        return Math.round(value * 100);
    }

    /**
     * Writes an amount the register's way: two decimals, a comma, no grouping,
     * and the euro sign — {@code 12,50 €}.
     *
     * @param {number} amountCents the amount in cents
     * @returns {string} the written amount
     */
    function moneyText(amountCents) {
        return (amountCents / 100).toFixed(2).replace('.', ',') + ' €';
    }

    /**
     * The running total « À APPORTER »: every denomination's count times its
     * value, in cents.
     *
     * @returns {number} the total in cents
     */
    function currentTotalCents() {
        var total = 0;
        Object.keys(countState).forEach(function (id) {
            total += cents(countValues[id]) * countState[id];
        });
        return total;
    }

    /**
     * Shows a denomination row's count and its amount.
     *
     * @param {Element} element the denomination row
     * @param {number} qty the count keyed
     * @param {number} value the denomination value in euros
     */
    function updateItemDisplay(element, qty, value) {
        element.querySelector('.item-qty').textContent = 'x ' + qty;
        element.querySelector('.item-total').textContent = moneyText(cents(value) * qty);
    }

    /**
     * Shows the running total, and announces the manager's endorsement above
     * the administered threshold.
     */
    function updateTotal() {
        var total = currentTotalCents();
        document.getElementById('totalDisplay').textContent = moneyText(total);
        var block = document.getElementById('managerBlock');
        var threshold = parseFloat(document.getElementById('apportForm').dataset.threshold);
        if (block) {
            block.style.display = total > cents(threshold) ? 'block' : 'none';
        }
    }

    /**
     * Arms the pad for one denomination row; the count already keyed is loaded
     * back, so a mistake is corrected rather than added to.
     *
     * @param {Element} element the denomination row
     */
    function selectItem(element) {
        if (selectedElement) {
            selectedElement.classList.remove('selected');
        }
        selectedElement = element;
        selectedElement.classList.add('selected');
        var id = element.getAttribute('data-id');
        var value = parseFloat(element.getAttribute('data-value'));
        countValues[id] = value;
        window.PosInput.setup({
            containerId: 'numpadArea',
            keyboardType: 'numpad',
            mode: 'integer',
            maxLength: 4,
            placeholder: '0',
            onChange: function (val) {
                var qty = parseInt(val, 10) || 0;
                countState[id] = qty;
                updateItemDisplay(element, qty, value);
                updateTotal();
            }
        });
        window.PosInput.prefill((countState[id] || 0).toString());
    }

    /**
     * Shows the rows of every denomination of the tab, with their counts, and the
     * total.
     */
    function refreshDisplay() {
        document.querySelectorAll('.count-item').forEach(function (item) {
            var id = item.getAttribute('data-id');
            var value = parseFloat(item.getAttribute('data-value'));
            if (!countValues[id]) {
                countValues[id] = value;
            }
            if (countState[id]) {
                updateItemDisplay(item, countState[id], value);
            }
        });
        updateTotal();
    }

    /**
     * Swaps the denomination tab, reusing the count page's fragment endpoint;
     * the counts already keyed survive the swap.
     *
     * @param {string} type the tab: bills, coins or rolls
     * @param {Element} btnElement the tab button
     * @returns {Promise<void>} settled once the tab is drawn
     */
    function switchTab(type, btnElement) {
        selectedElement = null;
        document.querySelectorAll('.tab-btn').forEach(function (b) { b.classList.remove('active'); });
        btnElement.classList.add('active');
        return fetch('/cash-count/fragment/' + type)
            .then(function (r) { return r.text(); })
            .then(function (html) {
                document.getElementById('listContainer').innerHTML = html;
                refreshDisplay();
            });
    }

    /**
     * Fills the posted total and the per-denomination detail. A count that adds
     * up to nothing is refused here rather than travelling to the server.
     *
     * @returns {boolean} true when the form may be posted
     */
    function prepareApport() {
        var total = currentTotalCents();
        if (total <= 0) {
            return false;
        }
        document.getElementById('amount').value = (total / 100).toFixed(2);
        document.getElementById('detail').value = JSON.stringify(countState);
        return true;
    }

    /**
     * Wires the denomination list of the apport screen, when the page carries it.
     */
    function boot() {
        var listContainer = document.getElementById('listContainer');
        if (!listContainer) {
            return;
        }
        listContainer.addEventListener('click', function (e) {
            var item = e.target.closest('.count-item');
            if (item && !item.hasAttribute('onclick')) {
                selectItem(item);
            }
        });
        refreshDisplay();
    }

    window.ApportCount = { switchTab: switchTab, prepareApport: prepareApport };
    // The coins and rolls tabs come from the count page's fragment, whose rows
    // call selectItem themselves; the delegated listener above leaves them alone.
    window.selectItem = selectItem;
    boot();
})();
