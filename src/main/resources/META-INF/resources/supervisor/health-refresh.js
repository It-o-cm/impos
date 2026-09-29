/*
 * Auto-refreshes the "Santé des échanges" tables every two seconds, in place, without
 * reloading the page (spec-sante-caisse §1.4, valuation-refresh pattern). Only the table
 * BODIES tagged with a data-refresh-url are replaced — the ticket uplink, the consumption
 * queue and the engine feeds — so the surrounding page, the "Rejouer" button (which sits
 * outside the bodies) and the scroll position are all left untouched. The refresh is
 * suspended while the browser tab is hidden.
 */
(function () {
    "use strict";

    var INTERVAL_MS = 2000;
    var targets = document.querySelectorAll("tbody[data-refresh-url]");
    if (targets.length === 0) {
        return;
    }
    var inFlight = {};

    /**
     * Fetches the latest rows for one table body and swaps them in; keeps the
     * current rows on a not-ok response or a transient error, and never lets two
     * requests for the same body overlap.
     */
    function refreshOne(tbody) {
        var url = tbody.getAttribute("data-refresh-url");
        if (inFlight[url]) {
            return;
        }
        inFlight[url] = true;
        fetch(url, { headers: { "Accept": "text/html" }, credentials: "same-origin" })
            .then(function (r) { return r.ok ? r.text() : null; })
            .then(function (html) {
                if (html != null) {
                    tbody.innerHTML = html;
                }
            })
            .catch(function () { /* transient error: keep the current rows, try again next tick */ })
            .then(function () { inFlight[url] = false; });
    }

    /**
     * Refreshes every tagged table body once, unless the tab is hidden.
     */
    function refresh() {
        if (document.hidden) {
            return;
        }
        targets.forEach(function (tbody) {
            refreshOne(tbody);
        });
    }

    setInterval(refresh, INTERVAL_MS);
})();
