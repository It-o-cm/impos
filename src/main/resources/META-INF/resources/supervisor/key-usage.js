/* Dynamic configured-key counters; text entry values are never observed (LC-13-05-01). */
(function () {
    "use strict";

    /** Returns a stable functional identity, including configured action arguments. */
    function keyOf(element) {
        if (element.dataset.posKey) return element.dataset.posKey;
        if (element.id) return location.pathname + ":" + element.id;
        var handler = element.getAttribute("onclick");
        if (handler) return location.pathname + ":" + handler;
        var form = element.closest("form");
        if (form) return location.pathname + ":" + form.getAttribute("action") + ":" + element.name + ":" + element.value;
        return location.pathname + ":" + element.getAttribute("href");
    }

    /** Observes function keys on operator screens, excluding all typing-pad keys. */
    function recordClick(event) {
        var element = event.target.closest("[data-pos-key],button,a,input[type=submit]");
        if (!element) return;
        if (element.closest(".pos-key")) return;
        if (element.disabled) return;
        if (!document.querySelector(".pos-terminal")) {
            if (location.pathname !== "/maintenance") return;
        }
        var key = keyOf(element).slice(0, 200);
        try {
            navigator.sendBeacon("/api/pos/key-use", key);
        } catch (unavailable) {
            // LC-13-05-01: unavailable counting must never cancel the operator's action.
        }
    }

    document.addEventListener("click", recordClick, true);
})();
