/* EXP-67: the CODE tenders of the payment screen — what their pad accepts, and where the scanner reads. */
(function () {
    'use strict';
    var armed = false;

    /**
     * The pad a code tender is keyed on.
     * <p>
     * A Resto Flash token is what the customer's phone shows — the text of a QR
     * payload, letters, digits and dashes (RestoFlashClient confirms any non-blank
     * token) — so its pad is the alphanumeric code keyboard, written in capitals.
     * A gift-card number and a SOGEC code stay on the digit pad.
     *
     * @param {string} mode the payment zone, e.g. "restoflash"
     * @returns {{keyboardType: string, mode: string, uppercase: boolean, maxLength: number}} the pad options
     */
    function codeEntry(mode) {
        if (mode === 'restoflash') {
            return { keyboardType: 'documentCode', mode: 'text', uppercase: true, maxLength: 64 };
        }
        return { keyboardType: 'numpad', mode: 'integer', uppercase: false, maxLength: 30 };
    }

    /**
     * Tells the register whether the RESTO FLASH zone is open, so that a code the
     * scanner reads meanwhile is the customer's token and not an article. Posted
     * only when the answer changes.
     *
     * @param {boolean} active true when the Resto Flash zone is the one open
     */
    function armRestoFlashScan(active) {
        if (active === armed) {
            return;
        }
        armed = active;
        fetch('/action/pay-restoflash/scan', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'armed=' + active
        }).catch(function () { armed = !active; });
    }

    window.PayCodeEntry = { codeEntry: codeEntry, armRestoFlashScan: armRestoFlashScan };
})();
